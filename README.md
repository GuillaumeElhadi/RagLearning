# RagLearning

Projet d'apprentissage : construire un RAG (*Retrieval-Augmented Generation*) de A à Z sur des documents PDF, avec Spring AI.

## Le principe en deux pipelines

Un RAG, ce sont deux processus distincts qui partagent la même base vectorielle :

```
INGESTION (une fois, puis à chaque nouveau document)
  PDF ──► DocumentReader ──► TextSplitter ──► EmbeddingModel ──► pgvector
          (texte + métadonnées)  (chunks)       (texte → vecteur)   (stockage)

REQUÊTE (à chaque question)
  Question ──► EmbeddingModel ──► pgvector ──► LLM ──► Réponse
               (même modèle !)   (top-k chunks   (lit le TEXTE
                                  les plus proches) des chunks)
```

| Rôle | Outil | Pourquoi |
|------|-------|----------|
| Modèle d'embedding (« le bibliothécaire ») | Ollama + `bge-m3` (variante `bge-m3-8k`) | Local, gratuit, multilingue (français/anglais), textes longs ; ~1,1 Go, vecteurs de 1024 dimensions |
| Base vectorielle | PostgreSQL 18 + extension pgvector | Calcule les distances entre vecteurs |
| LLM (« l'élève qui répond ») | Anthropic Claude Haiku 5.5 | Rédige la réponse à partir du texte des chunks |

Le modèle d'embedding et le LLM sont **indépendants** : Claude ne voit jamais les vecteurs, seulement le texte des chunks retrouvés.

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

# 2. Lancer l'application (ou via IntelliJ)
./mvnw spring-boot:run
```

Au premier démarrage, Spring AI crée lui-même les extensions, la table `devportal_knowledge` et son index.

Les PDF à indexer sont placés dans le dossier `sources/` (configurable, voir plus bas). Au démarrage, l'application **ingère tous les PDF de ce dossier** (voir [Ingestion](#ingestion)).

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
| `rag.ingestion.source-dir=sources` | Dossier des PDF à ingérer, relatif au dossier de lancement. Valeur par défaut surchargeable sans toucher au fichier : une variable d'environnement ou un argument de ligne de commande est prioritaire sur `application.properties` (*relaxed binding* : `RAG_INGESTION_SOURCEDIR`, `--rag.ingestion.source-dir=...`). |

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
sources/*.pdf
   │  CommandLineRunner (RagLearningApplication)
   ▼
RagIngestorService.ingestPdf()
   ├─ ParagraphPdfDocumentReader   1 Document par entrée du plan du PDF (section / chapitre)
   ├─ TokenTextSplitter            redécoupe les documents trop longs (1500 tokens)
   └─ VectorStore.add()            ajoute les chunks par lots séquentiels de 100
```

La barre de progression est affichée par fichier et avance uniquement après la réussite d'un lot. Elle indique donc les chunks dont l'appel au vector store est terminé, pas l'avancement interne d'Ollama ou de PostgreSQL.

### Historique Slack

Les conversations Slack sont indexées comme un document par fil afin de conserver le contexte question/réponse. Une classification déterministe en Java associe chaque fil à une catégorie (`TIMEOUT_CHANGE`, `GATEWAY_ACCESS`, `API_KEY_OAUTH`, `RUN_ISSUE`, `APPROVAL_VALIDATION` ou `OTHER`) et à un identifiant de modèle calculé à partir du texte normalisé. Le texte original des demandes est conservé, notamment les détails propres à chaque API, application et environnement.

Pour un même modèle autre que `OTHER`, une seule réponse de bot au texte exactement identique est conservée dans le canal ; les fils `OTHER` ne sont pas dédupliqués entre eux, et les réponses seulement similaires ne sont pas fusionnées. Les auteurs deviennent `USER A`, `USER B`, etc. (alias déterministes dans l'export), et les messages de bot sont étiquetés `RUNBOY`. Les mentions Slack sont converties en alias ; les affectations `clientID`, `client secret`, `login`, `password` et les adresses e-mail sont masquées avant l'envoi au vector store.

Cette anonymisation ne peut pas reconnaître les noms de personnes ou les secrets écrits en texte libre sans marqueur explicite. Pour un export partagé ou une ingestion en production, il faut aussi contrôler les données à la source et vérifier les textes nettoyés avant indexation.

| Composant | Rôle | Choix |
|-----------|------|-------|
| `ParagraphPdfDocumentReader` | Lit le PDF et produit un `Document` (texte + métadonnées) par entrée du **plan** (*outline* / signets) | Malgré son nom, il découpe par **section** et non par paragraphe : c'est la stratégie « par structure ». Préférable au découpage par page, car une page n'est pas une frontière de sens. |
| `TokenTextSplitter` | Découpe les `Document` trop longs en chunks | `chunkSize` de 1500 tokens (800 par défaut). Un document plus court reste en un seul chunk : avec cette valeur, environ 99 % des fils Slack restent entiers, ce qui garde ensemble la question et sa résolution. Les tokens sont comptés avec un tokenizer différent de celui de `bge-m3` : c'est une approximation, d'où la marge par rapport à la limite de 8192. |
| `VectorStore` (pgvector) | Vectorise chaque chunk via le modèle d'embedding, puis l'insère par lots de 100 | On n'appelle jamais Ollama directement. |

Le bean `TokenTextSplitter` est déclaré dans `BeansConfig` et injecté **avec son type précis** : si plusieurs `DocumentTransformer` coexistent un jour, injecter l'interface rendrait le choix ambigu.

### Prérequis du lecteur par plan

`ParagraphPdfDocumentReader` exige que le PDF ait un **plan** (des signets), ce qui n'est pas la même chose qu'une page « Sommaire » imprimée. Pour vérifier :

- **Aperçu (macOS)** : *Présentation → Table des matières*. Une arborescence de chapitres cliquables = plan présent.
- **Le lecteur lui-même** lève une exception si le plan est absent ; utiliser alors `PagePdfDocumentReader`.
- **Par code** : `document.getDocumentCatalog().getDocumentOutline()` (PDFBox) renvoie `null` sans plan.

### ⚠️ Ré-ingestion involontaire

L'ingestion s'exécute **à chaque démarrage**, et donc aussi :

- à **chaque recompilation**, car `spring-boot-devtools` redémarre l'application (`restartedMain` dans les logs) ;
- à **chaque lancement des tests**, car `@SpringBootTest` exécute les `CommandLineRunner`.

Chaque passage duplique tous les chunks. À rendre désactivable avant de travailler sur la recherche.

## Le modèle de données

Spring AI crée la table `devportal_knowledge`, avec **une ligne par chunk** :

| Colonne | Contenu |
|---------|---------|
| `id` | Identifiant du chunk (UUID, via l'extension `uuid-ossp`) |
| `content` | Le **texte** du chunk. Indispensable : un vecteur ne se « décompresse » pas, et c'est ce texte qui est envoyé au LLM. |
| `metadata` | JSON libre : fichier source et page pour un PDF ; `source`, `channel`, `threadId`, `sourceDocumentId` pour un fil Slack |
| `embedding` | Le vecteur du chunk, `vector(1024)` : ses coordonnées sur la « carte du sens » |

Un index **HNSW** (`vector_cosine_ops`) accélère la recherche des plus proches voisins : il évite de comparer la question à chaque ligne, au prix d'un résultat *approximatif*. Vérifier sa présence avec `\d devportal_knowledge`.

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
Question ──► RetrievalService ──► 5 chunks les plus proches
                                      │
                                      ├──► /rag/related-chunks : retourne les chunks
                                      │
                                      └──► QuestionAnsweringService ──► Claude Haiku ──► réponse
                                                                           (/rag/ask)
```

`RetrievalService` utilise la recherche par similarité de pgvector avec `topK=5`. Chaque résultat est exposé sous forme de `RetrievedChunk`, avec son score, ses métadonnées et son texte. `QuestionAnsweringService` fournit à Claude la question et ces chunks comme contexte. Son instruction système lui demande de ne pas inventer de réponse lorsque les ressources RAG ne la contiennent pas. Le client de chat est configuré dans `BeansConfig`; `SimpleLoggerAdvisor` ajoute la journalisation de l'appel au modèle.

Les deux routes attendent la question dans le paramètre `userRequest` :

```bash
# Inspecter les chunks trouvés sans appeler le LLM
curl -G --data-urlencode "userRequest=Quelle est la définition de X ?" \
  http://localhost:8080/rag/related-chunks

# Poser une question au RAG
curl -G --data-urlencode "userRequest=Quelle est la définition de X ?" \
  http://localhost:8080/rag/ask
```

`/rag/related-chunks` renvoie une liste de chunks (`score`, `metadata`, `content`). `/rag/ask` renvoie la réponse générée dans un tableau JSON contenant une chaîne. Il est utile de commencer par la première route pour distinguer un problème de recherche d'un problème de génération.

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

-- Repartir de zéro avant une nouvelle ingestion
TRUNCATE devportal_knowledge;
```

Pour voir le SQL exécuté par Spring, activer temporairement `logging.level.org.springframework.jdbc.core=DEBUG`. Les avertissements `already exists, skipping` au démarrage sont normaux : Spring AI rejoue ses `CREATE ... IF NOT EXISTS`.

## Règles à retenir

- **Même modèle d'embedding** pour les documents et pour les questions : deux modèles = deux cartes incompatibles.
- **Changer de modèle d'embedding = tout ré-indexer.** Les PDF sources restent la source de vérité, les vecteurs sont un dérivé jetable.
- **`dimensions` suit le modèle**, pas l'inverse.
- Les vecteurs servent uniquement à **retrouver** ; le LLM ne lit que du **texte**.
- **Sous Ollama, la longueur maximale d'un texte à vectoriser est min(`num_ctx`, `num_batch`)**, 2048 par défaut. Toujours vérifier la limite effective plutôt que celle annoncée par le modèle.
- **Préférer un échec bruyant à une perte silencieuse** : `truncate=false` plutôt qu'une troncature invisible.
- **La référence pour les propriétés d'une bibliothèque Spring est son catalogue** (`META-INF/spring-configuration-metadata.json` dans le JAR, celui qu'utilise l'autocomplétion d'IntelliJ), pas la documentation : une propriété documentée mais absente du catalogue peut être ignorée sans avertissement.

## Problèmes connus à l'ingestion

Aucun n'est bloquant (l'ingestion va au bout), mais les deux premiers dégradent la **qualité des chunks**. Un RAG ne retrouve pas ce que l'extraction a abîmé : *garbage in, garbage out*.

| Message | Cause | Conséquence |
|---------|-------|-------------|
| `ERROR ForkPDFLayoutTextStripper : Error sorting text positions` / `Comparison method violates its general contract!` | Bug de Spring AI : sa règle de tri des fragments de texte n'est pas cohérente sur certaines mises en page, et le tri Java abandonne. L'exception est rattrapée. | Sur les pages concernées, le texte peut être dans le désordre. |
| `WARN PDType0Font : No Unicode mapping for CID+... in font CambriaMath` | Les formules mathématiques du PDF sont stockées comme des glyphes sans table de correspondance vers des caractères. | Formules perdues ou caractères illisibles dans le `content`. Aucun réglage de PDFBox ne les récupère. |
| Beaucoup d'espaces et de sauts de ligne dans le `content` | Le *Layout*TextStripper imite la mise en page visuelle avec des espaces et des retours à la ligne. | Tokens gaspillés (des sections courtes donnent 2 chunks) et sens dilué dans les vecteurs. |
| `WARNING: A restricted method in java.lang.System has been called` | Netty charge du code natif sans autorisation explicite (Java récent). | Aucune. Option JVM indiquée dans le message pour le faire taire. |
| `ERROR DnsServerAddressStreamProviders : Unable to load ...MacOSDnsServerAddressStreamProvider` | Résolveur DNS natif macOS de Netty absent du classpath ; repli sur le résolveur par défaut. | Aucune pour des appels à `localhost`. |

**Pistes pour les formules illisibles**, de la plus simple à la plus lourde :

1. Supprimer les caractères irrécupérables (zone d'usage privé Unicode `\p{Co}`, caractère de remplacement `�`) pour qu'ils ne polluent pas les vecteurs.
2. Ingérer la **source** du PDF (Word, LaTeX, HTML) quand elle existe.
3. Faire retranscrire les pages concernées par un **modèle multimodal** (image de la page → Markdown + LaTeX), en ne traitant que les pages signalées par les warnings.
4. Des outils spécialisés d'analyse de documents (souvent en Python).

Les messages PDFBox peuvent être réduits avec `logging.level.org.apache.pdfbox.pdmodel.font=ERROR`.

## Évaluer le RAG

Un RAG peut échouer à deux endroits : il **retrouve les mauvais chunks**, ou il **retrouve les bons mais répond mal**. On les teste séparément, dans l'ordre :

1. **La recherche seule, sans LLM** : `VectorStore.similaritySearch(...)` avec la question et un *top-k*. Pour chaque résultat, afficher le score, la section ou la page d'origine (`metadata`) et le début du texte.
2. **La génération** : prompt construit à partir des chunks retrouvés et de la question, envoyé à Claude.

**Jeu d'évaluation** : quelques questions sur le PDF dont on connaît la réponse **et la page**, en mélangeant questions précises (une définition) et questions larges. Critère de la recherche : la bonne page apparaît-elle dans les cinq chunks retournés par `/rag/related-chunks` ? Pour la génération, vérifier que `/rag/ask` répond à partir du contexte et reconnaît les questions sans réponse dans les ressources. Ce jeu sert à comparer objectivement chaque changement (nettoyage du texte, taille de chunk, lecteur…).

## Progression

- [x] Infrastructure : PostgreSQL + pgvector via Docker Compose
- [x] Configuration Spring AI : datasource, Ollama, pgvector, Anthropic
- [x] Ingestion : lire un PDF, découper en chunks, stocker les vecteurs
- [ ] Ingestion désactivable (éviter les doublons avec devtools et les tests)
- [ ] Nettoyage du texte extrait avant découpage
- [ ] Jeu de questions d'évaluation
- [x] Recherche : retrouver les cinq chunks les plus proches d'une question (`/rag/related-chunks`)
- [x] Génération : fournir la question et les chunks à Claude Haiku (`/rag/ask`)
- [ ] Refactoring en architecture hexagonale
- [ ] Comparaison avec l'advisor Spring AI (`QuestionAnswerAdvisor`)
