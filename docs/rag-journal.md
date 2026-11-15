# Journal d'expérimentation du RAG

Ce document raconte comment le RAG a été construit, ce qui a été essayé, ce qui a été mesuré et ce qu'on en a appris. Le [README](../README.md) décrit **comment le projet fonctionne** ; ce journal explique **pourquoi il est ainsi**.

Il vit avec le projet : chaque nouvelle expérience y ajoute une entrée, dans le même commit que le code qu'elle concerne.

## Comment ajouter une expérience

Copier ce gabarit à la fin de la [chronologie](#2-chronologie-des-expériences) :

```markdown
### E<n> — <titre court> (<date>)

- **Constat** : ce qu'on a observé (avec un exemple ou un chiffre).
- **Hypothèse** : pourquoi on pense que ça se produit.
- **Changement** : ce qu'on a modifié (classe, propriété, prompt, données).
- **Mesure** : avant / après, idéalement avec le banc d'évaluation.
- **Leçon** : ce qu'on retiendrait pour un autre RAG.
```

Puis ajouter une ligne à l'[historique des mesures](#3-historique-des-mesures).

---

## 1. Où en est le RAG

### Le pipeline

```
INGESTION (CommandLineIngestor, si rag.ingestion.enabled=true)
  export Slack (JSON)
    → SlackDocumentReader        1 document par fil (question + discussion)
        ├─ SlackRequestClassifier   catégorie de la demande + identifiant de gabarit
        ├─ SlackAnonymizer          alias, liens, UUID, secrets, e-mails, demandeurs
        └─ SlackMarkupCleaner       gabarit du bot, emojis, gras, champs vides
       + métadonnées : threadId, date, year, humanReplyCount, requestCategory…
    → TextCleanerTransformer     espaces et lignes vides
    → TokenTextSplitter          1500 tokens (≈ 99 % des fils restent entiers)
    → VectorStore.add()          bge-m3-8k (Ollama) → table devportal_knowledge (pgvector)

REQUÊTE
  question
    → RetrievalService           top 10, filtre humanReplyCount > 0
    → ThreadContextFormatter     <thread id="…" date="…"> par fil
    → QuestionAnsweringService   prompt system + date du jour + fils + question
    → Claude Haiku               réponse sourcée et datée
```

### Les choix actuels

| Élément | Choix | Pourquoi |
|---------|-------|----------|
| Source | Export Slack du canal de run | Questions réelles et leurs résolutions |
| Unité de document | Un fil Slack | La question et sa résolution restent ensemble |
| Modèle d'embedding | `bge-m3` (variante `bge-m3-8k`), 1024 dimensions | Multilingue (fils en anglais et en français), textes longs |
| Contexte d'embedding | 8192 tokens (`num_ctx` + `num_batch`) | Par défaut, Ollama n'en lit que 2048 |
| Découpage | 1500 tokens | Seuls ≈ 1 % des fils sont coupés |
| Base vectorielle | PostgreSQL 18 + pgvector, index HNSW cosinus | Stack connue, pas de nouvelle infrastructure |
| Recherche | top 10, fils avec au moins une réponse humaine | Voir [E9](#e9--écarter-les-fils-sans-réponse-humaine) |
| Génération | Claude Haiku, prompt system dans `prompts/system-prompt.st` | Voir [E10](#e10--prompt-system-dates-et-citations) |

---

## 2. Chronologie des expériences

### E1 — Premier RAG sur un livre PDF (2026-10-25)

Le projet a commencé sur un PDF technique, avant de passer à Slack. Les leçons restent valables pour toute source documentaire.

- **Constat** : le texte extrait était plein d'espaces et de sauts de ligne, de caractères illisibles (`������`), et le sommaire du livre se retrouvait découpé en chunks.
- **Causes** :
  - le lecteur imite la mise en page avec des espaces : des tokens gaspillés, et un sens dilué ;
  - les formules mathématiques étaient dessinées avec des glyphes sans correspondance Unicode ;
  - le plan du PDF (ses signets) n'indiquait que la page, pas la position du titre dans la page : le lecteur par plan découpait au mauvais endroit, et les titres de section ne correspondaient plus au texte ;
  - les métadonnées utilisaient la page physique du fichier, et non la numérotation imprimée (26 pages d'écart).
- **Changements** : un `DocumentTransformer` de nettoyage placé **avant** le découpage, puisque le découpeur compte des tokens.
- **Mesure** : 461 → 341 chunks (−26 %) pour le même contenu.
- **Leçons** :
  - *garbage in, garbage out* : l'extraction est souvent la partie la plus difficile d'un RAG ;
  - tout le texte n'est pas bon à indexer : un sommaire contient tous les mots-clés du livre et aucune réponse, il remonte donc pour beaucoup de questions et prend la place de vrais passages ;
  - le PDF est un format d'impression, pas un format structuré : convertir en Markdown, ou repartir de la source, est souvent plus robuste.

### E2 — Le squelette de bout en bout (2026-10-25)

- **Démarche** : faire passer une question de bout en bout avant d'améliorer les données (*walking skeleton*), pour pouvoir mesurer l'effet de chaque amélioration sur une vraie réponse.
- **Constats** :
  - séparer la **recherche** (`/rag/related-chunks`) de la **génération** (`/rag/ask`) permet de savoir si une mauvaise réponse vient de mauvais documents ou d'un mauvais traitement par le LLM ;
  - un score de similarité **se lit en relatif, pour un modèle donné** : avec `nomic-embed-text`, une question hors sujet obtenait encore ≈ 0,40, et une bonne question ≈ 0,60 ;
  - la recherche vectorielle renvoie **toujours** `topK` résultats, même quand aucun n'est pertinent ;
  - avec la consigne « si les documents ne contiennent pas la réponse, dis-le », Claude refuse d'inventer sur une question hors sujet.
- **Piège rencontré** : une réponse vide. Le modèle produisait d'abord un bloc de **réflexion** (*thinking*), puis le bloc de texte ; le code lisait le premier. Diagnostic grâce au `SimpleLoggerAdvisor` et à la réponse complète (`chatResponse()`).
- **Leçons** :
  - observer la requête et la réponse brutes dès qu'un comportement surprend ;
  - le contexte doit être passé au modèle comme **paramètre** du modèle de prompt, jamais par concaténation : le code des documents contient des accolades.

### E3 — Passage à Slack et à `bge-m3` (2026-11-15)

- **Changement** : la source devient l'export du canal Slack de run, et le modèle d'embedding passe de `nomic-embed-text` (768 dimensions) à `bge-m3` (1024).
- **Conséquences** :
  - tous les vecteurs ont dû être recalculés : deux modèles = deux « cartes du sens » incompatibles ;
  - la colonne `vector(768)` ne pouvait pas recevoir des vecteurs de 1024 : `initialize-schema` **crée** la table, mais ne la modifie jamais.
- **Leçons** :
  - plus de dimensions ne veut pas dire plus de nuances : la qualité d'un modèle dépend surtout de son entraînement. Les vrais atouts de `bge-m3` ici sont le multilingue et les textes longs ;
  - un fil en français remonte maintenant pour une question en anglais : le multilingue se vérifie dans les résultats ;
  - la source de vérité, ce sont les documents d'origine ; les vecteurs sont un dérivé jetable.

### E4 — La limite cachée d'Ollama (2026-11-15)

- **Constat** : `bge-m3` peut lire 8192 tokens, mais l'API d'Ollama ne traitait que **2048** tokens, et tronquait la suite **sans erreur**.
- **Démarche** : mesurer la limite effective avec `prompt_eval_count`, au lieu de croire la fiche du modèle.
- **Découvertes** :
  - la propriété `spring.ai.ollama.embedding.num-ctx` figure dans la documentation de Spring AI, mais **n'existe pas** dans le code de la version 2.0.2 : elle était ignorée sans avertissement. La référence est le catalogue du JAR (`META-INF/spring-configuration-metadata.json`) ;
  - `num_ctx` seul ne suffisait pas : un modèle d'embedding lit tout le texte **en un seul lot**, donc la limite réelle est **min(`num_ctx`, `num_batch`)**.
- **Changement** : une variante `bge-m3-8k` (`docker/ollama/Modelfile`) avec `num_ctx` et `num_batch` à 8192, et `truncate=false` pour échouer au lieu de tronquer.
- **Mesure** : 7502 tokens traités sans erreur, et `ollama ps` affiche un contexte de 8192 pendant l'ingestion.
- **Leçons** :
  - une troncature silencieuse est l'un des pires bugs d'un RAG : les données semblent ingérées, mais leur fin a disparu ;
  - préférer un échec bruyant à une perte silencieuse.

### E5 — Le découpage adapté aux fils Slack (2026-11-15)

- **Mesure préalable** : distribution des longueurs de fils (≈ 6 575 fils) : médiane ≈ 150 tokens, 95 % < 800, 99 % < 1 550.
- **Constat** : avec 800 tokens (valeur par défaut), 5 % des fils étaient coupés. Ce sont les fils longs, souvent les incidents complexes, et le chunk de la résolution perdait sa question.
- **Changement** : `chunkSize` 1500 → ≈ 1 % des fils coupés.
- **Leçon** : choisir la taille de chunk à partir de la **distribution réelle** des documents, pas d'une valeur par défaut.

### E6 — Réduire le bruit des messages (2026-11-15)

- **Constat** : sur la question *« I want to update access restriction to my api »*, les cinq résultats parlaient bien du sujet, mais avec des scores très serrés (0,677 à 0,693), et le fil le plus utile était 5e.
- **Bruit identifié** :
  - le **gabarit du formulaire du bot** (`_tag:devportal_`, `:helmet_with_white_cross:`, `*Submitted by*`, champs vides), répété dans des centaines de fils : il rapproche les vecteurs de fils sans rapport entre eux ;
  - les **URL** et les **UUID** : des chaînes aléatoires sans aucun sens pour le modèle.
- **Changements** :
  - `SlackAnonymizer` : un lien devient son libellé, ou son chemin sans hôte ni paramètres ; un UUID devient `[UUID]`, en gardant le préfixe de type (`api-[UUID]`) ;
  - `SlackMarkupCleaner` : suppression du gabarit, des emojis et des champs vides ; `*Environment* UAT` devient `Environment: UAT`.
- **Mesure** :

  | | Avant | Après |
  |---|---|---|
  | Meilleur score | 0,693 | 0,712 |
  | Écart 1er → 5e | 0,016 | 0,026 |

  Les fils purement humains, sans gabarit, ont exactement **le même score** qu'avant ; seuls les fils du bot ont gagné ≈ 0,02. C'est la preuve que le gabarit diluait leur sens.
- **Effet inattendu** : le fil le plus utile est **sorti** du top 5.
- **Leçons** :
  - anonymiser et nettoyer améliore à la fois la confidentialité et la pertinence ;
  - une amélioration « en moyenne » peut dégrader un cas précis : d'où la nécessité d'un jeu d'évaluation ([E11](#e11--le-jeu-dévaluation)).

### E7 — Augmenter le top-k pour analyser (2026-11-15)

- **Changement** : `topK` 5 → 10.
- **Constats** :
  - la réponse de Claude couvre plus de cas, mais elle est plus diluée, et n'explique toujours pas le cas principal ;
  - le fil le plus utile n'est même pas dans le top 10 ;
  - les scores sont très plats (0,712 → 0,676) : des dizaines de fils « demande → done » ressemblent à la question sans y répondre.
- **Diagnostic** : un **décalage de vocabulaire**. La question dit *access restriction* ; le fil qui explique le concept parle de *visibility*, *catalog*, *publication*.
- **Leçon** : augmenter le top-k coûte des tokens sans résoudre un problème de **classement**. C'est un outil d'analyse, pas une solution.

### E8 — Fuite de noms et minimisation des données (2026-11-15)

- **Constat** : en échantillonnant les fils courts, de vrais noms apparaissaient. Le formulaire du bot donnait parfois le demandeur en texte brut, et non sous forme de mention Slack.
- **Changements** :
  - le nom du champ `Submitted by` des messages du bot est traité comme une identité, et reçoit un alias ; ses autres occurrences dans le fil aussi ;
  - suppression des champs d'e-mail des records d'export (`user_directory`, `members.email`, `user_email`, `creator_email`) : **une donnée qu'on ne charge pas ne peut pas fuir**.
- **Vérifications** : `content ~ 'Submitted by:? (?!USER )'` → 0 ; recherche des formes « Prénom NOM » restantes : seulement des termes techniques.
- **Risque résiduel** : un nom cité en texte libre, hors du formulaire (« merci Julie »), n'est pas détecté.
- **Piège** : `@JsonIgnoreProperties(ignoreUnknown = true)` sur un champ **n'empêche pas** son chargement. Il faut supprimer le champ du record.
- **Leçons** :
  - vérifier l'anonymisation **sur les données réelles**, pas seulement par des tests unitaires ;
  - en production, les contextes envoyés au LLM contiennent ce qui est en base : une fuite dans les données est une fuite vers un service tiers.

### E9 — Écarter les fils sans réponse humaine (2026-11-15)

- **Constat** : un tiers des fils font moins de 200 caractères. Dans un échantillon de 20, environ 17 étaient des **demandes du bot restées sans réponse** (« Timeout Change for api: X »), qui ressemblent beaucoup aux questions mais n'y répondent jamais. Un fil court pouvait pourtant être précieux (« cette fonctionnalité n'existe pas ») : la longueur n'est pas le bon critère.
- **Changement** : métadonnée `humanReplyCount` à l'ingestion, filtre `humanReplyCount > 0` à la recherche.
- **Mesure** : 2 185 fils sur 6 561 écartés. Sur la question de référence, le fil le plus utile entre dans le top 10, au 10e rang.
- **Limites** : une relance du demandeur compte comme une réponse ; un fil « demande → done » passe le filtre sans contenir d'explication.
- **Leçon** : **marquer plutôt que supprimer**. Une métadonnée filtrable garde les données, et permet de changer d'avis sans réingérer.

### E10 — Prompt system, dates et citations (2026-11-15)

- **Constats sur les réponses** :
  1. fidèles au contexte, mais sans sources ;
  2. une information de 2019 tantôt signalée comme ancienne, tantôt affirmée au présent ;
  3. le lien n'était pas fait entre un symptôme (« exposer l'API sur Internet a résolu le problème ») et le fil qui l'explique.
- **Changements** :
  - la **date** de chaque fil en métadonnée, et la **date du jour** dans le message user ;
  - un **formateur de contexte** : un bloc `<thread id="…" date="…">` par fil, sans les scores ni les métadonnées techniques ;
  - un **prompt system** versionné (`src/main/resources/prompts/system-prompt.st`), avec des consignes : faire confiance au fil le plus récent et signaler ce qui date de plus de deux ans, combiner les fils qui s'expliquent mutuellement, citer chaque point (date, fil), ne jamais deviner les valeurs masquées.
- **Résultat** : la réponse commence par l'explication du concept, relie enfin le symptôme à sa cause, cite chaque point et traite les dates de façon cohérente.
- **Point d'attention** : en 2026, presque tous les fils ont plus de deux ans. La consigne signale alors comme « peut-être dépassés » des cas sans doute encore valables. À ajuster : monter le seuil, ou ne signaler l'ancienneté qu'en cas de contradiction avec un fil plus récent.
- **Ce qu'on n'a pas fait** : filtrer les vieux fils à la recherche. Un vieux fil pertinent reste utile si le modèle sait qu'il est ancien.
- **Leçons** :
  - le prompt system corrige le **traitement** des documents par le LLM, pas leur **sélection** : il ne fera jamais apparaître un fil que la recherche n'a pas trouvé ;
  - un modèle ne connaît pas la date du jour de façon fiable : il faut la lui donner ;
  - un comportement que le prompt ne demande pas explicitement varie d'un appel à l'autre.

### E11 — Le jeu d'évaluation (2026-11-15)

- **Pourquoi** : jusque-là, chaque changement était jugé sur **une seule question**, à l'œil. E6 a montré qu'une amélioration moyenne pouvait dégrader un cas précis.
- **Ce qu'il contient** : `src/test/resources/evaluation/retrieval-questions.json`, 10 questions réelles reformulées (en français, alors que les fils sont surtout en anglais), chacune avec le ou les fils qui y répondent le mieux. Les références ont été vérifiées en base : elles existent et ont des réponses humaines.
- **Comment l'utiliser** :
  ```bash
  RAG_EVALUATION=true ./mvnw test -Dtest=RetrievalEvaluationTest
  ```
  Le test lance la vraie recherche jusqu'à 30 fils et affiche le rang du premier fil de référence pour chaque question, puis trois indicateurs :
  - **Hit@10** : part des questions dont un bon fil arrive jusqu'au modèle ;
  - **Hit@5** : même chose, avec une exigence plus forte ;
  - **MRR** : moyenne de 1/rang ; il récompense un bon fil placé **haut**.
- **Mesure de référence** :

  | # | Rang | | # | Rang |
  |---|---|---|---|---|
  | 1 | 1 | | 6 | 2 |
  | 2 | 4 | | 7 | 4 |
  | 3 | 2 | | 8 | 7 |
  | 4 | 1 | | 9 | **11** |
  | 5 | **18** | | 10 | **23** |

  Hit@5 = 60 %, Hit@10 = 70 %, MRR = 0,383.
- **Analyse** :
  - l'hypothèse « les fils très longs, découpés, seront mal classés » était **fausse** : leurs questions obtiennent les rangs 4, 2 et 1 ;
  - les trois échecs reposent sur des **termes exacts** : un code HTTP (`401`, `504`), une durée, un nom d'environnement (`PROD` contre `PREP`). Un embedding capture le sens général, mais distingue mal ces détails.
- **Leçons** :
  - on ne choisit pas une amélioration à l'intuition : on la mesure contre une référence, et on vérifie que les autres questions ne se dégradent pas ;
  - 10 questions est un petit échantillon (une question = 10 points de Hit@10). Ajouter au jeu chaque question réelle mal traitée.

---

## 3. Historique des mesures

| Date | Configuration | Hit@5 | Hit@10 | MRR |
|------|---------------|-------|--------|-----|
| 2026-11-15 | `bge-m3-8k`, chunk 1500, nettoyage + anonymisation, filtre `humanReplyCount > 0`, top 10 | 60 % | 70 % | 0,383 |

---

## 4. Points d'attention pour un RAG en production

### Données et ingestion

- **Comprendre la source avant de la vectoriser.** Échantillonner, mesurer les longueurs, repérer les gabarits répétés. Chaque problème trouvé ici l'a été en lisant les données.
- **Tout le texte n'est pas bon à indexer** : sommaires, gabarits, annonces, demandes sans réponse. Les marquer en métadonnée plutôt que les supprimer.
- **Nettoyer avant de découper**, puisque le découpeur compte des tokens.
- **Choisir l'unité de document selon le sens** : un fil Slack, une section, un ticket. Pas une page, ni une taille fixe arbitraire.
- **Rendre l'ingestion idempotente** : des identifiants stables et un upsert évitent les doublons. Attention aux chunks **orphelins** quand le découpage change : vider la table, ou supprimer les chunks d'un document avant de le réécrire.
- **Désactiver l'ingestion par défaut** : elle ne doit jamais se déclencher par accident (redémarrage de devtools, tests `@SpringBootTest`, nouveau développeur).

### Confidentialité

- **Anonymiser avant de vectoriser** : tout ce qui est en base finira dans un contexte envoyé au LLM.
- **Minimiser** : ne pas charger les champs personnels inutiles.
- **Vérifier sur les données réelles**, avec des requêtes de contrôle, pas seulement avec des tests unitaires.
- **En production, se poser aussi la question des droits d'accès** : un utilisateur ne doit retrouver que les documents qu'il a le droit de voir.

### Modèle d'embedding et infrastructure

- **Même modèle pour les documents et les questions** ; changer de modèle = tout réingérer.
- **`dimensions` suit le modèle**, et la colonne vectorielle ne se modifie pas toute seule.
- **Vérifier les limites effectives** (contexte, lots), pas celles annoncées : mesurer avec un texte plus long que la limite.
- **Préférer l'échec à la troncature** (`truncate=false`).
- **La documentation peut mentir, le catalogue du JAR non** : une propriété inconnue est ignorée sans avertissement.
- **Gérer le schéma comme du code** : en production, des migrations versionnées (Liquibase) plutôt que `initialize-schema`.

### Recherche

- **Tester la recherche seule**, sans le LLM : c'est elle qui décide de ce que le modèle peut savoir.
- **Lire les scores en relatif** : ils dépendent du modèle, et ne sont pas des pourcentages de pertinence.
- **Un seuil de similarité filtre, il ne reclasse pas.** Il sert surtout à ne rien renvoyer pour une question hors sujet, et se calibre pour chaque modèle.
- **Augmenter le top-k est un outil d'analyse**, pas une solution à un problème de classement.
- **Les embeddings sont faibles sur les termes exacts** (codes d'erreur, noms d'environnement, numéros de version) : la recherche hybride (vecteurs + mots-clés) est le remède classique.

### Génération et prompt system

- **Le prompt system agit sur le traitement des documents, pas sur leur sélection.**
- **Structurer le contexte** : un bloc par document, avec ses métadonnées utiles (date, identifiant), sans bruit technique.
- **Demander explicitement** ce qu'on attend : citer les sources, signaler l'ancienneté, ne pas inventer, ne pas deviner les valeurs masquées. Ce qui n'est pas demandé varie d'un appel à l'autre.
- **Donner la date du jour** si l'ancienneté compte.
- **Penser à l'injection de prompt** : les documents viennent d'utilisateurs, et un message pourrait contenir des instructions adressées au modèle. Le prompt system doit traiter le contenu des fils comme des données, jamais comme des consignes.
- **Observer les réponses brutes** (`SimpleLoggerAdvisor`) : blocs de réflexion, tokens consommés, raison d'arrêt.

### Évaluation

- **Construire un jeu d'évaluation tôt**, avec des questions réelles et des références vérifiées.
- **Mesurer la recherche et la génération séparément.**
- **Garder un historique des mesures**, avec la configuration de chaque mesure.
- **Enrichir le jeu** avec chaque question mal traitée en usage réel.

---

## 5. Prochaines expériences

Par ordre de priorité, chacune à mesurer contre la référence de la section 3 :

1. **Recherche hybride** : ajouter la recherche plein texte de PostgreSQL (`tsvector`) et fusionner les classements (*Reciprocal Rank Fusion*). Cible : les questions 5, 9 et 10, qui reposent sur des termes exacts.
2. **Expansion de requête** : faire reformuler la question en plusieurs variantes par un LLM (`MultiQueryExpander` de Spring AI) avant la recherche. Cible : le décalage de vocabulaire (E7).
3. **Résumé structuré à l'ingestion** : faire réécrire chaque fil en *Problème / Cause / Solution*, et vectoriser ce résumé. Coût : un appel LLM par fil.
4. **Reranking** : récupérer 30 candidats, puis les faire reclasser par un modèle qui lit la question et chaque fil ensemble.
5. **Ajuster la consigne sur les dates** (E10).
6. **Calibrer un seuil de similarité** pour les questions hors sujet, avec `bge-m3`.
7. **Évaluer la génération**, et pas seulement la recherche : fidélité au contexte, sources correctes, par exemple avec un LLM juge.
8. **Industrialiser** : tests d'intégration autonomes (Testcontainers), schéma géré par Liquibase, refactoring en architecture hexagonale.

---

## 6. Glossaire

| Terme | Définition |
|-------|------------|
| **Chunk** | Morceau de document vectorisé et stocké comme une ligne de la base. |
| **Embedding** | Vecteur qui représente le sens d'un texte : ses coordonnées sur une « carte du sens ». |
| **Top-k** | Nombre de chunks les plus proches renvoyés par la recherche. |
| **Score de similarité** | Proximité entre la question et un chunk (ici 1 − distance cosinus). Relatif au modèle. |
| **Recherche hybride** | Combinaison de la recherche vectorielle (sens) et de la recherche par mots-clés (termes exacts). |
| **Reranking** | Second classement des candidats par un modèle qui lit la question et chaque document ensemble. |
| **Hit@k** | Part des questions dont un document de référence est dans les k premiers résultats. |
| **MRR** | *Mean Reciprocal Rank* : moyenne de 1/rang du premier document de référence. |
| **Walking skeleton** | Version minimale qui fonctionne de bout en bout, améliorée ensuite étape par étape. |
| **Upsert** | Insertion qui met à jour la ligne si l'identifiant existe déjà. |
