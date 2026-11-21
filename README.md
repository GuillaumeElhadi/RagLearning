# RagLearning

Projet d'apprentissage : construire un RAG (*Retrieval-Augmented Generation*) de A à Z avec Spring AI.

Le RAG répond aux questions de support sur le Developer Portal (API management avec Kong et Ping Identity) à partir de l'**historique anonymisé d'un canal Slack de support** : chaque fil (question, échanges, résolution) devient un document. Une première phase a porté sur un livre PDF ; ce code a été retiré, ses leçons restent dans le journal (E1).

Le **pourquoi** de chaque choix, les expériences menées et leurs mesures sont dans le [journal d'expérimentation](docs/rag-journal.md). Ce README décrit **comment le projet fonctionne**.

## Le principe en deux pipelines

Un RAG, ce sont deux processus distincts qui partagent la même base vectorielle :

```
INGESTION (à la demande, rag.ingestion.enabled=true)
  export Slack ──► SlackDocumentReader ──► TokenTextSplitter ──► EmbeddingModel ──► pgvector
  (JSON)           1 Document par fil :     (chunks de 1500       (texte → vecteur)   (stockage)
                   nettoyage, PUIS           tokens au plus)
                   anonymisation, métadonnées

REQUÊTE (à chaque question)
  Question ──► EmbeddingModel ──► pgvector ──────────► ThreadContextFormatter ──► LLM ──► Réponse
               (même modèle !)   top 10 fils répondus    <thread id date>…</thread>   (prompt system :
                                 (responderReplyCount > 0)                             citations, dates)
```

| Rôle | Outil | Pourquoi |
|------|-------|----------|
| Modèle d'embedding (« le bibliothécaire ») | Ollama + `bge-m3` (variante `bge-m3-8k`) | Local, gratuit, multilingue (français/anglais), textes longs ; ~1,1 Go, vecteurs de 1024 dimensions |
| Base vectorielle | PostgreSQL 18 + extension pgvector | Calcule les distances entre vecteurs |
| LLM (« l'élève qui répond ») | Anthropic Claude Haiku 5.5 | Rédige la réponse à partir du texte des fils retrouvés |

Le modèle d'embedding et le LLM sont **indépendants** : Claude ne voit jamais les vecteurs, seulement le texte des fils retrouvés.

## Organisation du code

Le code est regroupé **par fonctionnalité**, un dossier par étape du RAG, et non par type de classe : ce qui change ensemble vit ensemble.

```
fr.elhadi.raglearning
 ├─ ingestion/    RagIngestorService, CommandLineIngestor : charge l'export Slack dans la base
 │   └─ slack/    lecture, nettoyage, anonymisation et classification des fils ; modèle de l'export
 ├─ retrieval/    RetrievalService, RetrievedChunk : retrouve les fils pertinents
 │   │            ReciprocalRankFusion : fusion de classements (RRF), avec poids par classement
 │   │            HybridRetrievalService : recherche dense + mots-clés, fusion RRF ; rend des fils, pas encore branchée sur l'API
 │   └─ keyword/  KeywordSearchService : recherche par mots-clés classée par fil
 │                KeywordExtractor : extrait les mots-clés d'une question avec un LLM, puis les nettoie
 ├─ generation/   QuestionAnsweringService, ThreadContextFormatter : construit le contexte, appelle le LLM
 ├─ api/          RagController : routes /rag/related-chunks et /rag/ask
 └─ config/       BeansConfig : beans partagés (découpeur de texte, client de chat)
```

Les dépendances vont dans un seul sens : `api` utilise `generation` et `retrieval`, `generation` utilise `retrieval`, et `ingestion` est indépendante des trois autres (elle ne partage que la base). Les tests suivent la même arborescence ; les bancs d'évaluation sont dans `evaluation/`, car ils traversent plusieurs blocs.

## Prérequis

- **Java 27** et Maven (le wrapper `./mvnw` est fourni)
- **Docker** (Colima sur Mac) pour PostgreSQL
- **Ollama** installé et lancé, avec le modèle d'embedding téléchargé **puis** sa variante créée :
  ```bash
  ollama pull bge-m3
  ollama create bge-m3-8k -f docker/ollama/Modelfile
  ollama show bge-m3-8k --parameters   # doit afficher num_ctx 8192 et num_batch 8192
  ```
  Voir [Ollama : longueur maximale d'un texte](#ollama--longueur-maximale-dun-texte) pour la raison de cette variante.
- **Une clé API Anthropic**, exposée dans la variable d'environnement `ANTHROPIC_API_KEY`
  (dans IntelliJ : *Run Configuration → Environment variables*). Ne jamais l'écrire en dur dans un fichier versionné.

## Démarrer

```bash
# 1. Lancer la base vectorielle (depuis la racine du projet)
docker compose -f docker/compose.yaml up -d

# 2. Lancer l'application (ou via IntelliJ) : elle sert les routes de recherche
./mvnw spring-boot:run
```

Au premier démarrage, Spring AI crée lui-même les extensions, la table `devportal_knowledge` et son index.

**L'ingestion est désactivée par défaut** (`rag.ingestion.enabled=false`), pour qu'elle ne se déclenche jamais par accident (redémarrage de devtools, tests `@SpringBootTest`). Pour ingérer les exports Slack (`*.json`) du dossier `rag.ingestion.source-dir` :

```bash
# Repartir d'une table vide (voir « Ingestion idempotente »)
psql -h localhost -p 5433 -U raguser ragdb -c "TRUNCATE devportal_knowledge;"

./mvnw spring-boot:run -Dspring-boot.run.arguments=--rag.ingestion.enabled=true
```

⚠️ Le dossier `sources/` contient des données réelles : il n'est pas versionné, et seule sa version **anonymisée** atteint la base.

## Configuration

### `docker/compose.yaml` : la base vectorielle

| Élément | Valeur | Signification |
|---------|--------|---------------|
| `image` | `pgvector/pgvector:pg18-trixie` | Image PostgreSQL officielle avec l'extension pgvector **déjà installée**. pgvector n'est pas un service séparé : c'est une bibliothèque chargée dans le processus Postgres. |
| `POSTGRES_DB` | `ragdb` | Base créée au premier démarrage |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `raguser` / … | Identifiants créés au premier démarrage |
| `ports` | `5433:5432` | Postgres écoute sur 5432 dans le conteneur, exposé sur **5433** côté Mac |
| `volumes` | `pgvector-data:/var/lib/postgresql` | Persiste les données entre deux `down`/`up`. Depuis PostgreSQL 18, on monte le dossier parent (et non plus `.../data`). |

⚠️ Les variables `POSTGRES_*` ne sont lues **qu'à la première initialisation**, quand le volume est vide. Pour les changer, il faut repartir d'un volume propre : `docker compose -f docker/compose.yaml down -v`.

### `application.properties` : l'application

**Connexion à la base**

| Propriété | Signification |
|-----------|---------------|
| `spring.datasource.url=jdbc:postgresql://localhost:5433/ragdb` | URL JDBC. Le préfixe `jdbc:postgresql:` permet à Spring de choisir le driver. |
| `spring.datasource.username` / `password` | Les identifiants définis dans le compose |

**Modèle d'embedding**

| Propriété | Signification |
|-----------|---------------|
| `spring.ai.model.embedding=ollama` | Choisit le **fournisseur** d'embedding (et non le modèle) |
| `spring.ai.ollama.embedding.model=bge-m3-8k` | Choisit le **modèle** Ollama utilisé pour vectoriser : `bge-m3` avec un contexte porté à 8192 tokens (voir `docker/ollama/Modelfile`) |
| `spring.ai.ollama.embedding.truncate=false` | Si un texte dépasse la limite du modèle, Ollama renvoie une **erreur** au lieu de tronquer la fin **en silence**. Un échec visible vaut mieux qu'une perte invisible. |

**Base vectorielle**

| Propriété | Signification |
|-----------|---------------|
| `spring.ai.vectorstore.pgvector.initialize-schema=true` | Autorise Spring AI à créer extensions, table et index au démarrage |
| `spring.ai.vectorstore.pgvector.schema-name=public` | Schéma PostgreSQL de la table |
| `spring.ai.vectorstore.pgvector.table-name=devportal_knowledge` | Nom de la table des chunks (par défaut `vector_store`) |
| `spring.ai.vectorstore.pgvector.distance-type=cosine_distance` | Mesure de proximité entre vecteurs : la distance cosinus, standard pour le texte |
| `spring.ai.vectorstore.pgvector.dimensions=1024` | Taille des vecteurs. **Doit être égale à la dimension du modèle d'embedding** (1024 pour `bge-m3`, visible avec `ollama show` : *embedding length*). |

**LLM**

| Propriété | Signification |
|-----------|---------------|
| `spring.ai.model.chat=anthropic` | Choisit Anthropic comme fournisseur du modèle de chat |
| `spring.ai.anthropic.chat.model=claude-haiku-5-5` | Modèle Claude utilisé pour générer les réponses |
| `spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}` | Clé lue depuis l'environnement, jamais en dur |

**Application**

| Propriété | Signification |
|-----------|---------------|
| `rag.ingestion.enabled=false` | Active l'ingestion au démarrage (`CommandLineIngestor` et `RagIngestorService` n'existent que si la valeur est `true`). Laisser `false` dans le fichier versionné, et l'activer en argument au besoin. |
| `rag.ingestion.source-dir` | Dossier des exports Slack (`*.json`) à ingérer. Valeur par défaut surchargeable sans toucher au fichier : une variable d'environnement ou un argument de ligne de commande est prioritaire sur `application.properties` (*relaxed binding* : `RAG_INGESTION_SOURCEDIR`, `--rag.ingestion.source-dir=...`). |

⚠️ `initialize-schema` **crée** la table si elle n'existe pas, mais **ne la modifie jamais**. Après un changement de `dimensions` ou de `table-name`, supprimer l'ancienne table (`DROP TABLE ...`) **avant** de redémarrer : dans PostgreSQL, un nom d'index est unique dans tout le schéma, et un ancien index du même nom empêcherait la création de celui de la nouvelle table (`already exists, skipping`). Ne pas utiliser `remove-existing-vector-store-table=true` pour cela : la table serait supprimée **à chaque démarrage**.

⚠️ Ne pas écrire `rag.ingestion.source-dir=${rag.ingestion.source-dir:sources}` : la propriété se référence elle-même et Spring échoue au démarrage (référence circulaire).

**Diagnostic (optionnel)**

```properties
logging.level.org.springframework.jdbc.core=DEBUG
```
Affiche chaque requête SQL exécutée par Spring (création du schéma, insertions, recherches).

## Ingestion

Le pipeline suit le modèle ETL de Spring AI : `DocumentReader` (Extract) → `DocumentTransformer` (Transform) → `DocumentWriter` (Load).

```
sources/*.json (export Slack)
   │  CommandLineIngestor (seulement si rag.ingestion.enabled=true)
   ▼
RagIngestorService.ingestSlackHistory()
   ├─ SlackDocumentReader    1 Document par fil, texte nettoyé puis anonymisé, métadonnées
   ├─ TokenTextSplitter      redécoupe les fils trop longs (1500 tokens)
   ├─ identifiants stables   même fil, même découpage ⇒ mêmes identifiants de chunks
   └─ VectorStore.add()      vectorise et insère par lots séquentiels de 100
```

La barre de progression avance uniquement après la réussite d'un lot. Elle indique donc les chunks dont l'appel au vector store est terminé, pas l'avancement interne d'Ollama ou de PostgreSQL.

### Le lecteur Slack

| Composant | Rôle |
|-----------|------|
| `SlackDocumentReader` | Regroupe les messages par fil, écarte les réponses vides, calcule les métadonnées, produit un `Document` par fil |
| `SlackMarkupCleaner` | Retire le bruit Slack : caractères illisibles, étiquettes d'espace de travail, emojis `:shortcode:`, champs de formulaire vides, gras, espaces et sauts de ligne superflus |
| `SlackAnonymizer` | Nettoie (via `SlackMarkupCleaner`), **puis** anonymise ; déduplique les réponses de bot identiques |
| `SlackRequestClassifier` | Classe chaque fil (`TIMEOUT_CHANGE`, `GATEWAY_ACCESS`, `API_KEY_OAUTH`, `RUN_ISSUE`, `APPROVAL_VALIDATION`, `OTHER`) et lui attribue un identifiant de modèle de demande |

**Anonymisation** — elle est toujours la **dernière transformation du texte** : une étape qui supprimerait ou réécrirait des caractères après elle pourrait recomposer une donnée que les règles n'avaient pas reconnue (voir E12 dans le journal).

| Donnée | Remplacement |
|--------|--------------|
| Auteurs, mentions `<@U…>`, noms en texte brut du formulaire (`Submitted by …`) | `USER A`, `USER B`… (alias déterministes) ; messages de bot : `RUNBOY` |
| Liens Slack | Leur libellé, sinon leur chemin |
| URL complètes | `[URL]` |
| Noms d'hôte sans schéma (extensions publiques et internes listées) | `[HOST]` |
| Adresses IPv4 | `[IP]` |
| UUID (préfixe de type conservé : `api-[UUID]`) | `[UUID]` |
| En-têtes `Authorization`, clés d'API, JWT, `clientID`, `client secret`, `login`, `password` | masqués |
| Adresses e-mail | `[REDACTED_EMAIL]` ; les champs e-mail de l'export ne sont même pas chargés (minimisation) |

Ces règles ne peuvent pas reconnaître un nom ou un secret écrit en texte libre sans marqueur. Pour un export partagé ou une ingestion en production, il faut aussi contrôler les données à la source et vérifier le texte stocké (voir [Vérifications utiles](#vérifications-utiles)).

**Découpage** — `TokenTextSplitter` (bean de `BeansConfig`, injecté avec son type précis) avec un `chunkSize` de 1500 tokens. Un fil plus court reste en un seul chunk : environ 99 % des fils restent entiers, ce qui garde ensemble la question et sa résolution. Les tokens sont comptés avec un tokenizer différent de celui de `bge-m3` : c'est une approximation, d'où la marge par rapport à la limite de 8192.

### Ingestion idempotente

Les chunks reçoivent des **identifiants stables** et pgvector fait un **upsert** (`ON CONFLICT DO UPDATE`) : réingérer le même export ne crée pas de doublons. En revanche, si le découpage ou le nettoyage change, d'anciens chunks peuvent rester **orphelins**. D'où le `TRUNCATE devportal_knowledge` avant chaque réingestion complète.

## Le modèle de données

Spring AI crée la table `devportal_knowledge`, avec **une ligne par chunk** :

| Colonne | Contenu |
|---------|---------|
| `id` | Identifiant du chunk (UUID stable, calculé à partir du fil et de la position du chunk) |
| `content` | Le **texte** du chunk, nettoyé et anonymisé. Indispensable : un vecteur ne se « décompresse » pas, et c'est ce texte qui est envoyé au LLM. |
| `metadata` | Type **`json`** (pas `jsonb`). Pour un fil Slack : `source`, `channel`, `channelId`, `threadId`, `sourceDocumentId`, `author`, `date` (ISO, UTC), `year`, `requestCategory`, `requestTemplateId`, `humanReplyCount`, `responderReplyCount` |
| `embedding` | Le vecteur du chunk, `vector(1024)` : ses coordonnées sur la « carte du sens » |

`responderReplyCount` compte les réponses humaines d'une **autre personne que le demandeur** (l'auteur du message racine, ou la personne citée dans le `Submitted by` du formulaire). C'est sur elle que filtre la recherche.

Un index **HNSW** (`vector_cosine_ops`, nommé `devportal_knowledge_index`) accélère la recherche des plus proches voisins : il évite de comparer la question à chaque ligne, au prix d'un résultat *approximatif*. Vérifier sa présence avec `\d devportal_knowledge`.

⚠️ La colonne `metadata` étant en `json`, l'opérateur `?` n'existe pas (et JDBC le prendrait pour un paramètre) et un `GROUP BY metadata` échoue : utiliser `metadata->>'clé'` et grouper par `id`.

## Ollama : longueur maximale d'un texte

Par défaut, Ollama n'alloue que **2048 tokens** de contexte, même pour un modèle capable d'en lire 8192 comme `bge-m3`. Au-delà, la fin du texte est **ignorée sans erreur**. D'où la variante `bge-m3-8k`, définie dans `docker/ollama/Modelfile` :

```
FROM bge-m3
PARAMETER num_ctx 8192
PARAMETER num_batch 8192
```

- **`num_ctx` seul ne suffit pas.** Un modèle d'embedding (type BERT) lit tout le texte **en un seul lot de calcul**, contrairement à un LLM qui peut lire un long prompt par morceaux. La longueur maximale réelle est donc **min(`num_ctx`, `num_batch`)**.
- **Spring AI 2.0.2 ne permet pas de régler ces valeurs pour l'embedding.** La propriété `spring.ai.ollama.embedding.num-ctx` figure dans la documentation, mais n'existe pas dans le code : seules `model`, `truncate` et `keep-alive` sont liées. Une propriété inconnue est ignorée sans avertissement.
- **Les vecteurs de `bge-m3-8k` sont identiques à ceux de `bge-m3`** : mêmes poids, seule la quantité de texte lue change.

**Vérifier la limite effective** : l'API d'Ollama renvoie le nombre de tokens réellement traités (`prompt_eval_count`). Avec un texte d'environ 9000 tokens, il doit valoir 8192 :

```bash
curl -s http://localhost:11434/api/embed -d "{
  \"model\": \"bge-m3-8k\",
  \"input\": \"$(python3 -c 'print("test de contexte " * 3000)')\"
}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["prompt_eval_count"])'
```

2048 signifie que la variante n'est pas utilisée ou que `num_batch` manque. Pendant une ingestion, `ollama ps` affiche aussi le contexte du modèle chargé.

## Recherche et question/réponse

La recherche et la génération sont séparées en deux services :

```
Question ──► RetrievalService ──► 10 chunks les plus proches, fils répondus uniquement
                                      │
                                      ├──► /rag/related-chunks : retourne les chunks
                                      │
                                      └──► QuestionAnsweringService ──► Claude Haiku ──► réponse
                                             ThreadContextFormatter                       (/rag/ask)
```

- **`RetrievalService`** : recherche par similarité de pgvector, `topK=10`, avec le filtre `responderReplyCount > 0` (les fils sans réponse d'une autre personne que le demandeur sont gardés en base mais écartés de la recherche). Chaque résultat est un `RetrievedChunk` : score, métadonnées, texte.
- **`ThreadContextFormatter`** : met chaque chunk dans une balise `<thread id="…" date="…">…</thread>`. Sans résultat, le contexte indique « No thread matched this question. ».
- **`QuestionAnsweringService`** : envoie à Claude la date du jour, les fils et la question. Le prompt system (`src/main/resources/prompts/system-prompt.st`, sans accolades car c'est un gabarit) lui demande notamment de :
  - dire quand les fils ne contiennent pas la réponse ;
  - privilégier les fils récents et signaler ceux de plus de deux ans ;
  - présenter un fil comme « un cas passé », pas comme une règle ;
  - citer ses sources (date, identifiant du fil) ;
  - ne jamais deviner les valeurs masquées (`[HOST]`, `[UUID]`…) ni mentionner de personnes ;
  - répondre dans la langue de la question.

Il y a deux clients de chat, nommés dans `BeansConfig` : `questionAnsweringChatClient` (génération, prompt `system-prompt.st`) et `keywordExtractorChatClient` (extraction de mots-clés, prompt `keyword-extraction.st`). Chaque classe qui en reçoit un précise son nom avec `@Qualifier` (le fichier `lombok.config` demande à Lombok de recopier cette annotation sur le constructeur). Les deux utilisent le même modèle, Claude Haiku. `SimpleLoggerAdvisor` journalise l'appel au modèle (blocs de réflexion, tokens, raison d'arrêt). La réponse ignore les résultats vides, car un bloc de réflexion peut arriver en premier.

Les deux routes attendent la question dans le paramètre `userRequest` :

```bash
# Inspecter les chunks trouvés sans appeler le LLM
curl -G --data-urlencode "userRequest=Comment augmenter le timeout de mon API en PROD ?" \
  http://localhost:8080/rag/related-chunks

# Poser une question au RAG
curl -G --data-urlencode "userRequest=Comment augmenter le timeout de mon API en PROD ?" \
  http://localhost:8080/rag/ask
```

`/rag/related-chunks` renvoie une liste de chunks (`score`, `metadata`, `content`). `/rag/ask` renvoie la réponse générée. Commencer par la première route permet de distinguer un problème de recherche d'un problème de génération.

## Vérifications utiles

```sql
-- Extensions installées
SELECT extname FROM pg_extension;

-- pgvector sait calculer une distance (doit renvoyer 1)
SELECT '[1,2,3]'::vector <-> '[1,2,4]'::vector;

-- Nombre de chunks indexés
SELECT count(*) FROM devportal_knowledge;

-- Taille des vecteurs stockés (doit renvoyer 1024)
SELECT vector_dims(embedding) FROM devportal_knowledge LIMIT 1;

-- Détection des doublons : les deux nombres doivent être égaux
SELECT count(*), count(DISTINCT content) FROM devportal_knowledge;

-- Structure de la table et présence de l'index HNSW (psql)
\d devportal_knowledge

-- Fils répondus vs non répondus
SELECT (metadata->>'responderReplyCount')::int > 0 AS repondu, count(*)
FROM devportal_knowledge GROUP BY 1;

-- Contrôles d'anonymisation : doivent renvoyer 0
SELECT count(*) FROM devportal_knowledge WHERE content ~* '[a-z0-9.-]+@[a-z0-9-]+\.[a-z]{2,}';
SELECT count(*) FROM devportal_knowledge WHERE content ~ '(\d{1,3}\.){3}\d{1,3}';

-- Repartir de zéro avant une nouvelle ingestion
TRUNCATE devportal_knowledge;
```

Pour voir le SQL exécuté par Spring, activer temporairement `logging.level.org.springframework.jdbc.core=DEBUG`. Les avertissements `already exists, skipping` au démarrage sont normaux : Spring AI rejoue ses `CREATE ... IF NOT EXISTS`.

## Règles à retenir

- **Même modèle d'embedding** pour les documents et pour les questions : deux modèles = deux cartes incompatibles.
- **Changer de modèle d'embedding = tout ré-indexer.** Les sources restent la source de vérité, les vecteurs sont un dérivé jetable.
- **`dimensions` suit le modèle**, pas l'inverse.
- Les vecteurs servent uniquement à **retrouver** ; le LLM ne lit que du **texte**.
- **Anonymiser en dernier**, et vérifier le texte stocké avec des requêtes de contrôle, pas seulement avec des tests unitaires.
- **Mesurer avant et après chaque changement** avec le banc d'évaluation, et consigner le résultat dans le journal.
- **Sous Ollama, la longueur maximale d'un texte à vectoriser est min(`num_ctx`, `num_batch`)**, 2048 par défaut. Toujours vérifier la limite effective plutôt que celle annoncée par le modèle.
- **Préférer un échec bruyant à une perte silencieuse** : `truncate=false` plutôt qu'une troncature invisible.
- **La référence pour les propriétés d'une bibliothèque Spring est son catalogue** (`META-INF/spring-configuration-metadata.json` dans le JAR, celui qu'utilise l'autocomplétion d'IntelliJ), pas la documentation : une propriété documentée mais absente du catalogue peut être ignorée sans avertissement.

## Évaluer le RAG

Un RAG peut échouer à deux endroits : il **retrouve les mauvais fils**, ou il **retrouve les bons mais répond mal**. On les teste séparément, en commençant par la recherche.

### Le banc d'évaluation de la recherche

- **Jeu d'évaluation** : `src/test/resources/evaluation/retrieval-questions.json`, 30 questions (10 courantes, 10 contenant un terme rare, 10 requêtes courtes de type identifiant, repérées par le champ `"category"`), chacune avec ses formulations par langue (`"variants": {"fr": […], "en": […]}`) et le ou les fils de référence (`expectedThreadIds`) vérifiés à la main. Les traductions anglaises sont fidèles, sans vocabulaire tiré des fils.
- **Test** : `RetrievalEvaluationTest` interroge **quatre recherches** à la même profondeur de 30 : la recherche **dense** seule et la recherche **hybride** (dense + mots-clés, fusion RRF) avec trois pondérations dense:mots-clés (1:1, 2:1, 4:1) qui partagent les mêmes mots-clés au sein d'une exécution, classe les résultats par fil distinct, puis affiche, **pour chaque langue et chaque recherche**, le rang de la première référence pour chaque question (le pire rang si une langue a plusieurs formulations), ainsi que :
  - **Hit@5** et **Hit@10** : part des questions dont une référence est dans les 5 (ou 10) premiers fils ;
  - **MRR** : moyenne de 1/rang de la première référence ;
  - les **mots-clés extraits** par le LLM pour chaque formulation (l'extraction n'est pas déterministe : ils expliquent un écart de rang, et un petit écart peut n'être que du bruit). Le test espionne `KeywordExtractor` (`@MockitoSpyBean`) pour les noter sans changer son comportement. Il lance un appel au LLM par formulation : `ANTHROPIC_API_KEY` doit être définie.
- **Lancement** (il interroge la vraie base et Ollama, d'où la variable qui l'active) :
  ```bash
  RAG_EVALUATION=true RAG_EVALUATION_RUNS=5 ./mvnw test -Dtest=RetrievalEvaluationTest
  ```

Mesure actuelle, recherche dense seule : Hit@5 60 %, Hit@10 70 %, MRR 0,457 en français ; 60 %, 70 %, 0,453 en anglais. Les moyennes sont identiques, mais certaines questions bougent de plus de 10 rangs d'une langue à l'autre : lire les rangs question par question (voir E14). Les questions 5, 9 et 10 échouent : leurs références sont dans le top 30 mais mal classées. Recherche hybride (dense + mots-clés, RRF), une seule exécution : 80 %, 90 %, 0,560 en français ; 70 %, 80 %, 0,419 en anglais, soit un gain net en français et un bilan mitigé en anglais (voir E17). Moyenne de 5 exécutions (E18) : 72 %, 88 %, MRR 0,467 en français ; 54 %, 80 %, MRR 0,391 en anglais. L'hybride gagne en Hit@10 en français mais pas en MRR, et son MRR varie de 0,1 à 0,2 d'une exécution à l'autre : le 0,560 d'E17 était une exécution favorable. En pondérant la dense (E19), le MRR monte à 0,531 en français et 0,433 en anglais avec un rapport 4:1 (contre 0,475 et 0,380 à égalité), au prix de la question 10 ; ce réglage n'est pas le défaut du code. Sur les 10 questions à terme rare ajoutées (E20), la dense seule est déjà presque parfaite (Hit@5 100 %, MRR 0,82 en français et 0,87 en anglais) : l'hybride n'y apporte rien. Sur les 10 requêtes courtes de type identifiant (E21), la dense tombe à un MRR de 0,71 et l'hybride à égalité de poids monte à 0,89 en français et 0,84 en anglais : c'est là que la recherche par mots-clés sert. Mais la pondération idéale s'inverse d'un type de requête à l'autre (4:1 aide les questions longues, nuit aux requêtes courtes) : un poids fixe est un compromis. L'historique complet est dans la section « Historique des mesures » du [journal](docs/rag-journal.md).

⚠️ Ne jamais reprendre une question du jeu comme exemple dans un prompt : on ajusterait le système au jeu d'évaluation (sur-apprentissage), et les mesures ne vaudraient plus rien.

### Recherche par mots-clés (brique de la recherche hybride, E15)

`KeywordSearchService.searchByKeywords(keywords, limit)` est la seconde recherche de la recherche hybride : elle retourne des **identifiants de fils** classés, qui seront fusionnés avec ceux de la recherche dense par `ReciprocalRankFusion`. Elle n'est pas encore branchée sur `/rag/related-chunks` ni sur le banc d'évaluation.

Comportement :

- les mots-clés sont découpés comme la base découpe le texte (minuscules, coupure sur tout ce qui n'est pas lettre ou chiffre : `api-key` donne `api` et `key`), dédoublonnés **après** le découpage, et passés à la base comme **paramètre**, jamais concaténés au SQL ;
- un chunk est retenu s'il contient **au moins un** mot-clé (OU) ;
- chaque mot vaut son **IDF** (`ln(nombre de chunks / chunks contenant le mot)`), calculé par `ts_stat` : un mot rare pèse plus qu'un mot fréquent ;
- le score d'un fil est celui de son **meilleur chunk** (somme des IDF des mots-clés qu'il contient) ;
- tri par score, puis par date décroissante, puis par identifiant : l'ordre est déterministe ;
- seuls les fils avec `responderReplyCount > 0` sont retournés ; `limit` compte des **fils**, pas des chunks.

La requête SQL est expliquée pas à pas dans la Javadoc du service et dans le [journal](docs/rag-journal.md) (E15).

`KeywordExtractor` prépare les mots-clés à partir de la question. Il sépare deux rôles : le LLM propose des mots (`extract` : la question part telle quelle, la réponse est lue en sortie structurée `List<String>`), puis `normalize` applique une politique **déterministe** (retire les mots qui tombent sur les masques d'anonymisation `ip`, `host`, `uuid`, `redacted`, ignore les vides, plafonne à 8 mots). Le découpage des mots composés n'est **pas** dans l'extracteur : il est dans le service de recherche, qui doit tokeniser comme la base (voir E16). `normalize` est une fonction pure testée sans mock (`KeywordExtractorTest`).

Ses tests (`KeywordSearchServiceTest`) tournent sur un vrai PostgreSQL avec pgvector lancé par **Testcontainers** : Docker (Colima sur Mac) doit donc être démarré, et la base de l'application n'est pas utilisée.

```bash
./mvnw test -Dtest=KeywordSearchServiceTest
```

### Exploration : extraction de mots-clés (E13, E16)

`KeywordExtractionExplorationTest` affiche les mots-clés que `KeywordExtractor` extrait de chaque question du jeu, avec le prompt `src/main/resources/prompts/keyword-extraction.st`. Il a servi à explorer la recherche par mots-clés, dont l'hypothèse a été réfutée (voir E13), puis à vérifier l'extracteur (E16). Il appelle Anthropic : `ANTHROPIC_API_KEY` doit être définie dans l'environnement.

```bash
RAG_EVALUATION=true ./mvnw test -Dtest=KeywordExtractionExplorationTest
```

### La génération

À évaluer ensuite : `/rag/ask` répond-il à partir du contexte, cite-t-il les bons fils, reconnaît-il les questions sans réponse dans les fils ?

## Journal d'expérimentation

Ce qui a été essayé, mesuré et appris en construisant ce RAG est consigné dans [docs/rag-journal.md](docs/rag-journal.md), avec l'historique des mesures du banc d'évaluation et les points d'attention pour un RAG en production.

## Progression

- [x] Infrastructure : PostgreSQL + pgvector via Docker Compose
- [x] Configuration Spring AI : datasource, Ollama, pgvector, Anthropic
- [x] Première phase sur un livre PDF (code retiré, leçons dans E1)
- [x] Ingestion de l'historique Slack : un document par fil, découpage à 1500 tokens
- [x] Contexte effectif d'Ollama porté à 8192 tokens (`bge-m3-8k`)
- [x] Ingestion désactivable et idempotente
- [x] Nettoyage du bruit Slack, puis anonymisation en dernier
- [x] Recherche filtrée sur les fils répondus (`/rag/related-chunks`)
- [x] Génération avec prompt system, date du jour et citations (`/rag/ask`)
- [x] Banc d'évaluation de la recherche (Hit@5, Hit@10, MRR)
- [x] Exploration de la recherche par mots-clés : hypothèse réfutée (E13)
- [x] Questions d'évaluation en français et en anglais : pas d'effet de langue moyen, forte sensibilité à la formulation (E14)
- [x] Code réorganisé par fonctionnalité (`ingestion`, `retrieval`, `generation`, `api`)
- [ ] Mesure du bruit de formulation (reformulations dans la même langue)
- [ ] Reranking des 30 premiers candidats
- [x] Recherche hybride (branche `feature/hybrid-search`, pour apprendre la technique) : fusion RRF, recherche par mots-clés (E15), extraction des mots-clés par un LLM (E16), assemblage et comparaison dense / hybride (E17). Elle rend des fils et n'est pas branchée sur `/rag/ask`, qui demanderait de charger le texte d'un fil
- [ ] Évaluation de la génération
- [ ] Refactoring en architecture hexagonale
- [ ] Comparaison avec l'advisor Spring AI (`QuestionAnswerAdvisor`)

Les prochaines expériences, par priorité, sont détaillées dans le [journal](docs/rag-journal.md#5-prochaines-expériences).

