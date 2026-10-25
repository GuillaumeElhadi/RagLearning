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
| Modèle d'embedding (« le bibliothécaire ») | Ollama + `nomic-embed-text` | Local, gratuit, léger (~300 Mo) |
| Base vectorielle | PostgreSQL 18 + extension pgvector | Calcule les distances entre vecteurs |
| LLM (« l'élève qui répond ») | Anthropic Claude | Rédige la réponse à partir du texte des chunks |

Le modèle d'embedding et le LLM sont **indépendants** : Claude ne voit jamais les vecteurs, seulement le texte des chunks retrouvés.

## Prérequis

- **Java 27** et Maven (le wrapper `./mvnw` est fourni)
- **Docker** (Colima sur Mac) pour PostgreSQL
- **Ollama** installé et lancé, avec le modèle d'embedding téléchargé :
  ```bash
  ollama pull nomic-embed-text
  ```
- **Une clé API Anthropic**, exposée dans la variable d'environnement `ANTHROPIC_API_KEY`
  (dans IntelliJ : *Run Configuration → Environment variables*). Ne jamais l'écrire en dur dans un fichier versionné.

## Démarrer

```bash
# 1. Lancer la base vectorielle (depuis la racine du projet)
docker compose -f docker/compose.yaml up -d

# 2. Lancer l'application (ou via IntelliJ)
./mvnw spring-boot:run
```

Au premier démarrage, Spring AI crée lui-même les extensions, la table `vector_store` et son index.

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
| `spring.ai.ollama.embedding.model=nomic-embed-text` | Choisit le **modèle** Ollama utilisé pour vectoriser |

**Base vectorielle**

| Propriété | Signification |
|-----------|---------------|
| `spring.ai.vectorstore.pgvector.initialize-schema=true` | Autorise Spring AI à créer extensions, table et index au démarrage |
| `spring.ai.vectorstore.pgvector.schema-name=public` | Schéma PostgreSQL de la table |
| `spring.ai.vectorstore.pgvector.distance-type=cosine_distance` | Mesure de proximité entre vecteurs : la distance cosinus, standard pour le texte |
| `spring.ai.vectorstore.pgvector.dimensions=768` | Taille des vecteurs. **Doit être égale à la dimension du modèle d'embedding** (768 pour `nomic-embed-text`). |

**LLM**

| Propriété | Signification |
|-----------|---------------|
| `spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}` | Clé lue depuis l'environnement, jamais en dur |

**Application**

| Propriété | Signification |
|-----------|---------------|
| `rag.ingestion.source-dir=sources` | Dossier des PDF à ingérer, relatif au dossier de lancement. Valeur par défaut surchargeable sans toucher au fichier : une variable d'environnement ou un argument de ligne de commande est prioritaire sur `application.properties` (*relaxed binding* : `RAG_INGESTION_SOURCEDIR`, `--rag.ingestion.source-dir=...`). |

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
   ├─ TokenTextSplitter            redécoupe les sections trop longues (~800 tokens par défaut)
   └─ VectorStore.add()            appelle Ollama pour chaque chunk, puis insère dans vector_store
```

| Composant | Rôle | Choix |
|-----------|------|-------|
| `ParagraphPdfDocumentReader` | Lit le PDF et produit un `Document` (texte + métadonnées) par entrée du **plan** (*outline* / signets) | Malgré son nom, il découpe par **section** et non par paragraphe : c'est la stratégie « par structure ». Préférable au découpage par page, car une page n'est pas une frontière de sens. |
| `TokenTextSplitter` | Découpe les `Document` trop longs en chunks | Réglages par défaut (~800 tokens). Une section plus courte reste en un seul chunk. Les tokens sont comptés avec un tokenizer différent de celui de `nomic-embed-text` : c'est une approximation. |
| `VectorStore` (pgvector) | Vectorise chaque chunk via le modèle d'embedding, puis l'insère | On n'appelle jamais Ollama directement. |

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

Spring AI crée la table `vector_store`, avec **une ligne par chunk** :

| Colonne | Contenu |
|---------|---------|
| `id` | Identifiant du chunk (UUID, via l'extension `uuid-ossp`) |
| `content` | Le **texte** du chunk. Indispensable : un vecteur ne se « décompresse » pas, et c'est ce texte qui est envoyé au LLM. |
| `metadata` | JSON libre : fichier source, numéro de page… |
| `embedding` | Le vecteur du chunk, `vector(768)` : ses coordonnées sur la « carte du sens » |

Un index **HNSW** (`vector_cosine_ops`) accélère la recherche des plus proches voisins : il évite de comparer la question à chaque ligne, au prix d'un résultat *approximatif*.

## Vérifications utiles

```sql
-- Extensions installées
SELECT extname FROM pg_extension;

-- pgvector sait calculer une distance (doit renvoyer 1)
SELECT '[1,2,3]'::vector <-> '[1,2,4]'::vector;

-- Nombre de chunks indexés
SELECT count(*) FROM vector_store;

-- Taille des vecteurs stockés (doit renvoyer 768)
SELECT vector_dims(embedding) FROM vector_store LIMIT 1;

-- Détection des doublons : les deux nombres doivent être égaux
SELECT count(*), count(DISTINCT content) FROM vector_store;

-- Repartir de zéro avant une nouvelle ingestion
TRUNCATE vector_store;
```

Pour voir le SQL exécuté par Spring, activer temporairement `logging.level.org.springframework.jdbc.core=DEBUG`. Les avertissements `already exists, skipping` au démarrage sont normaux : Spring AI rejoue ses `CREATE ... IF NOT EXISTS`.

## Règles à retenir

- **Même modèle d'embedding** pour les documents et pour les questions : deux modèles = deux cartes incompatibles.
- **Changer de modèle d'embedding = tout ré-indexer.** Les PDF sources restent la source de vérité, les vecteurs sont un dérivé jetable.
- **`dimensions` suit le modèle**, pas l'inverse.
- Les vecteurs servent uniquement à **retrouver** ; le LLM ne lit que du **texte**.

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

**Jeu d'évaluation** : quelques questions sur le PDF dont on connaît la réponse **et la page**, en mélangeant questions précises (une définition) et questions larges. Critère de la recherche : la bonne page apparaît-elle dans le top 5 ? Ce jeu sert à comparer objectivement chaque changement (nettoyage du texte, taille de chunk, lecteur…).

## Progression

- [x] Infrastructure : PostgreSQL + pgvector via Docker Compose
- [x] Configuration Spring AI : datasource, Ollama, pgvector, Anthropic
- [x] Ingestion : lire un PDF, découper en chunks, stocker les vecteurs
- [ ] Ingestion désactivable (éviter les doublons avec devtools et les tests)
- [ ] Nettoyage du texte extrait avant découpage
- [ ] Jeu de questions d'évaluation
- [ ] Recherche : retrouver les chunks les plus proches d'une question
- [ ] Génération : construire le prompt et faire répondre Claude
- [ ] Refactoring en architecture hexagonale
- [ ] Comparaison avec l'advisor Spring AI (`QuestionAnswerAdvisor`)
