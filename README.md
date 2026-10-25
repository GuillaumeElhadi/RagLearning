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

Les PDF à indexer sont placés dans le dossier `sources/`.

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

**Diagnostic (optionnel)**

```properties
logging.level.org.springframework.jdbc.core=DEBUG
```
Affiche chaque requête SQL exécutée par Spring (création du schéma, insertions, recherches).

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
```

## Règles à retenir

- **Même modèle d'embedding** pour les documents et pour les questions : deux modèles = deux cartes incompatibles.
- **Changer de modèle d'embedding = tout ré-indexer.** Les PDF sources restent la source de vérité, les vecteurs sont un dérivé jetable.
- **`dimensions` suit le modèle**, pas l'inverse.
- Les vecteurs servent uniquement à **retrouver** ; le LLM ne lit que du **texte**.

## Progression

- [x] Infrastructure : PostgreSQL + pgvector via Docker Compose
- [x] Configuration Spring AI : datasource, Ollama, pgvector, Anthropic
- [ ] Ingestion : lire un PDF, découper en chunks, stocker les vecteurs
- [ ] Recherche : retrouver les chunks les plus proches d'une question
- [ ] Génération : construire le prompt et faire répondre Claude
- [ ] Refactoring en architecture hexagonale
- [ ] Comparaison avec l'advisor Spring AI (`QuestionAnswerAdvisor`)
