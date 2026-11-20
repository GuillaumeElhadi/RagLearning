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
- **Suite** : le code de cette première phase (`ingestPdf`, `TextCleanerTransformer`, la dépendance au lecteur PDF) a été supprimé le 2026-11-17, lors de la réorganisation des packages par fonctionnalité. Il reste dans l'historique Git.

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
- **Risque résiduel** : un nom cité en texte libre, hors du formulaire, n'est pas détecté. L'échantillon de [E12](#e12--bruit-résiduel-noms-dhôte-et-réponses-du-demandeur) en a montré deux formes : un prénom dans une salutation (« Hey … »), et un prénom en **signature** à la fin d'un message. Des règles sur les prénoms seuls provoqueraient trop de faux positifs : c'est un vrai sujet de production (détection d'entités nommées, ou relecture humaine avant indexation).
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

### E12 — Bruit résiduel, noms d'hôte et réponses du demandeur (2026-11-15)

- **Constat** : 383 fils passent le filtre de E9 tout en faisant moins de 300 caractères. Sur un échantillon de 20 :

  | Type | Nombre | Exemples |
  |---|---|---|
  | Réponse utile malgré la brièveté | ≈ 7 | timeout maximal de 30 s, pas de mise en veille en UAT, WebSocket non supporté |
  | Sans contenu exploitable | ≈ 11 | « done », « can you check now? », un simple « Thanks » du demandeur |
  | Annonces d'exploitation | 2 | migration de Kong 3 |

- **Deux défauts découverts** :
  - certains fils passaient le filtre uniquement grâce au **demandeur** lui-même (« Thanks », relance) : `humanReplyCount` compte toutes les réponses humaines ;
  - un **nom d'hôte écrit sans schéma** (`prep-developer.…`) échappait à l'anonymisation, qui n'attrapait que les URL commençant par `http(s)://`.
- **Changements** :
  - nouvelle métadonnée `responderReplyCount` : les réponses humaines d'une **autre personne que le demandeur**. Le demandeur est l'auteur du message racine, ou la personne mentionnée dans le `Submitted by` du formulaire. La recherche filtre désormais sur `responderReplyCount > 0` ;
  - nouvelle règle `[HOST]` pour les noms de domaine sans schéma, limitée aux extensions courantes pour épargner les noms de fichiers (`config.json`) et les paquets Java.
- **Limite** : quand le formulaire donne le demandeur en texte brut, on ne peut pas le relier à son compte Slack ; toutes les réponses humaines comptent alors.
- **Une fuite qui a résisté, et sa vraie cause** : après réingestion, un nom d'hôte interne restait en clair, alors que la règle le masquait dans les tests. Dans l'export, une partie du nom était **en gras** : `*ccdp*.priv…`. L'anonymisation ne reconnaissait pas ce texte coupé par les astérisques ; puis le nettoyage de la mise en forme, qui passait **après**, retirait le gras et recomposait le nom d'hôte. Le `TextCleanerTransformer` présentait le même risque, puisqu'il supprimait des caractères après l'anonymisation.
- **Correction** : le nettoyage (mise en forme, caractères illisibles, espaces) est désormais fait **avant** l'anonymisation, dans `SlackAnonymizer`, et le `TextCleanerTransformer` n'est plus utilisé pour Slack. Ajout d'une règle `[IP]` pour les adresses IPv4 internes, découvertes au passage dans l'export.
- **Diagnostic, étape par étape** : tester la règle sur le texte stocké (elle le masque) → vérifier que la ligne vient du code actuel (métadonnée récente présente) → afficher les octets (pas de caractère invisible) → chercher le texte **brut** dans l'export, par la phrase qui précède le nom d'hôte (le gras apparaît).
- **Mesure** :
  - 136 fils n'avaient reçu de réponses que de leur demandeur : ils sont désormais écartés de la recherche ;
  - après réingestion, plus aucun nom d'hôte ni aucune adresse IP en clair (contrôles SQL à 0) ;
  - banc d'évaluation :

    | # | Avant | Après | | # | Avant | Après |
    |---|---|---|---|---|---|---|
    | 1 | 1 | 1 | | 6 | 2 | **1** |
    | 2 | 4 | 4 | | 7 | 4 | **1** |
    | 3 | 2 | 2 | | 8 | 7 | 7 |
    | 4 | 1 | 2 | | 9 | 11 | 12 |
    | 5 | 18 | 18 | | 10 | 23 | 26 |

    Hit@5 et Hit@10 inchangés (60 % et 70 %) ; MRR 0,383 → **0,457**.
  - **Lecture prudente** : la hausse du MRR vient presque entièrement de deux questions passées au premier rang (6 et 7) ; la question 7 apporte à elle seule +0,075. Sur 10 questions, c'est un signal encourageant, pas une preuve. Les trois échecs (5, 9, 10) n'ont pas bougé : ce lot ne les visait pas, et ils confirment le besoin d'une recherche hybride.
- **Leçons** :
  - la longueur n'est pas un critère de qualité : un tiers des fils courts contient les réponses les plus nettes de la base ;
  - lire des échantillons fait apparaître des fuites que les tests unitaires ne pouvaient pas prévoir.
  - **l'anonymisation doit être la dernière transformation du texte** : toute étape qui supprime ou réécrit des caractères après elle peut recomposer une donnée sensible ;
  - pour diagnostiquer une fuite, comparer le texte stocké au texte **brut** de la source : c'est souvent entre les deux que le problème se cache.

### E13 — Recherche par mots-clés : une hypothèse réfutée (2026-11-15)

- **Constat** : les questions 5, 9 et 10 restent mal classées (rangs 18, 12 et 26). On supposait qu'elles reposaient sur des **termes exacts** (`401`, `504`, `prep`, `prod`) que les embeddings saisissent mal.
- **Hypothèse** : une **seconde recherche, indépendante**, par mots-clés (plein texte PostgreSQL), fusionnée avec la recherche vectorielle par *Reciprocal Rank Fusion* (score = Σ 1/(60 + rang)), ferait remonter les références. Avant de coder la fusion, on a mesuré en SQL ce que la recherche par mots-clés **seule** donnait pour ces trois questions.
- **Outils d'exploration** :
  - `to_tsvector('simple', content)` / `to_tsquery` / `@@` : `'simple'` ne fait ni racinisation ni suppression de mots vides, adapté à un corpus mêlant français et anglais. Le ET (`&`) est précis mais fragile, le OU (`|`) a besoin d'un bon score ;
  - `ts_rank_cd` ne connaît pas l'IDF et favorise les documents longs. On a donc calculé un score maison : la **somme des IDF** des mots trouvés, avec IDF = ln(nombre de chunks / nombre de chunks contenant le mot), obtenu via `ts_stat` ;
  - le rang exact d'une référence se calcule avec des fonctions de fenêtre : `rank() OVER (ORDER BY score DESC)` (rang optimiste) et `count(*) OVER () - rank() OVER (ORDER BY score ASC) + 1` (rang pessimiste). L'écart entre les deux est la taille du groupe d'ex aequo ;
  - pièges SQL : l'opérateur `?` n'existe pas sur `json` (et JDBC le prend pour un paramètre), `GROUP BY` sur une colonne `json` échoue (grouper par `d.id`).
- **Trois façons de choisir les mots** :
  1. **Les mots de la question, pondérés par l'IDF** : les mots-outils français (`quand`, `faut-il`) sont rares dans un canal surtout anglophone, donc jugés précieux, alors que les termes techniques sont fréquents, donc jugés banals. Les fils en français sans rapport remontaient. **L'IDF mesure la rareté, pas la pertinence.**
  2. **Des mots choisis à la main, en anglais** : question 5 au rang 13 (mieux que la recherche dense), question 9 au rang 18 (16 fils ex aequo), question 10 hors du top 10.
  3. **Des mots extraits par un LLM** (prompt `prompts/keyword-extraction.st`, exploré avec `KeywordExtractionExplorationTest`) : traduction en anglais, codes conservés, synonymes proches ajoutés.
- **Comportement du LLM** :
  - bien : tout en anglais, termes exacts conservés, synonymes justes sur le fond (`unauthorized`, `upstream`, `issuer`) ;
  - défauts : mots composés malgré la consigne (`api-key`, `gateway-timeout`, que PostgreSQL découpe en morceaux tous obligatoires) ; mots qui tombent sur les masques d'anonymisation (`ip`, `host`, `uuid` correspondent à `[IP]`, `[HOST]`, `[UUID]`) ; pas de `jwt` pour la question 10 (l'ajouter au prompt aurait été du sur-apprentissage sur le jeu d'évaluation).
- **Mesure** (rang de la meilleure référence, recherche par mots-clés seule, avec OU) :

  | Question | Dense | Mots à la main | Mots du LLM | Fils trouvés (LLM) |
  |---|---|---|---|---|
  | 5 | 18 | 13 | 42 à 48 | 651 |
  | 9 | 12 | 18 | 132 à 144 | 1 622 |
  | 10 | 26 | hors top 10 | 219 à 234 | 2 451 |

  Aucun code de production modifié : les mesures du banc sont inchangées.
- **Pourquoi ça échoue** :
  - **nos termes ne sont pas rares** : dans un canal de support d'une passerelle d'API, `401`, `504`, `kong`, `prod`, `token` apparaissent dans des centaines de fils. Un mot présent partout ne distingue pas un fil d'un autre, même quand c'est le bon mot ;
  - **les références sont laconiques** : `401 authentication`, `504 timeout`, `ping prep prod`. Elles n'emploient ni `unauthorized`, ni `openapi`, ni `upstream`, ni `gateway`. Les noms d'hôtes contenant `prod` ou `prep` ont été masqués en `[HOST]` ;
  - **les synonymes aident les concurrents** : chaque mot ajouté est un mot de plus que les **autres** fils peuvent posséder. Ils gonflent aussi un concept : `swagger`, `spec` et `openapi` font passer devant n'importe quel fil sur les specs Swagger, sans aucun 401 ;
  - **le score est trop pauvre** : la somme d'IDF ignore la fréquence du mot dans le fil et sa longueur. Résultat : des groupes de 12 à 24 fils ex aequo.
- **Décision** : ne pas coder la fusion RRF pour l'instant. Elle récompense l'accord entre les deux recherches : avec des rangs mots-clés aussi mauvais, elle ferait redescendre ces références plutôt que les remonter.
- **Indice pour la suite** : les trois références sont **dans le top 30 dense**. La recherche dense les trouve mais les classe mal. Le problème est l'**ordre**, pas la découverte : c'est le rôle du **reranking**.
- **Pistes non testées** : grouper les mots par concept, avec un OU dans le concept et un ET entre concepts (`(401 | unauthorized) & (swagger | openapi | spec)`), le LLM renvoyant des groupes ; pondérer les mots d'origine plus que les synonymes ; un vrai BM25 (extension ParadeDB `pg_search`).
- **Leçons** :
  - **mesurer une hypothèse avant de coder** : quelques requêtes SQL ont évité un service de fusion qui n'aurait rien amélioré ;
  - la recherche par mots-clés brille sur les **termes rares** (identifiant, nom d'application, code d'erreur peu fréquent), pas sur le vocabulaire quotidien du domaine ;
  - **l'expansion de requête échange de la précision contre du rappel** : elle aide quand la référence emploie le synonyme, et nuit dans tous les autres cas ;
  - pour un corpus mixte, la vraie valeur du LLM est la **traduction**, pas les synonymes ;
  - le scoring compte autant que le choix des mots ;
  - une recherche secondaire se juge à **ce qu'elle apporte à la fusion** : seulement sur les questions où elle classe la référence mieux que la recherche dense ;
  - **le jeu d'évaluation doit contenir des questions qui ont besoin de la technique testée** : le nôtre n'a aucune question à terme rare.

### E14 — La même question en français et en anglais (2026-11-17)

- **Constat** : les questions du jeu sont en français, alors que les fils sont surtout en anglais. `bge-m3` est multilingue, mais une recherche d'une langue à l'autre est en général un peu moins précise qu'une recherche dans la même langue.
- **Hypothèse** : une partie des mauvais classements vient de la **langue** de la question. Si c'est le cas, traduire la question avant la recherche serait une amélioration peu coûteuse.
- **Changement** (banc uniquement, aucun code de production modifié) :
  - le jeu d'évaluation passe à des **variantes par langue** : `"variants": {"fr": […], "en": […]}`. Chaque langue est une liste, pour pouvoir ajouter plus tard des reformulations ;
  - les traductions anglaises sont **fidèles**, sans vocabulaire tiré des fils (pas de `jwt`, d'`upstream` ni d'`unauthorized`), sans quoi on mesurerait la connaissance du corpus et non l'effet de la langue ;
  - `RetrievalEvaluationTest` calcule les rangs et les métriques **par langue**, sans jamais les mélanger. Il échoue si une question n'a pas toutes les langues, puisque des métriques sur 9 questions et sur 10 ne sont pas comparables. Avec plusieurs formulations dans une langue, il retient le **pire rang**, le choix le plus exigeant. Le rapport affiche une légende des colonnes.
- **Mesure** : la colonne FR redonne exactement la mesure de E12 (non-régression de la refactorisation).

  | # | FR | EN | | # | FR | EN |
  |---|---|---|---|---|---|---|
  | 1 | 1 | 1 | | 6 | 1 | 1 |
  | 2 | 4 | **16** | | 7 | 1 | 1 |
  | 3 | 2 | 4 | | 8 | 7 | **2** |
  | 4 | 2 | 2 | | 9 | 12 | **9** |
  | 5 | 18 | 22 | | 10 | 26 | 17 |

  | Langue | Hit@5 | Hit@10 | MRR |
  |---|---|---|---|
  | FR | 60 % | 70 % | 0,457 |
  | EN | 60 % | 70 % | 0,453 |

- **Lecture** :
  - en moyenne, **aucune différence** ; question par question, **tout bouge** : trois questions progressent en anglais (8, 9, 10), trois reculent (2, 3, 5), et les écarts se compensent. La question 9 entre dans le top 10 en anglais, la question 2 en sort ;
  - **seconde hypothèse réfutée** : « le rang s'améliore quand la question est dans la langue du fil ». Les sept fils de référence vérifiés sont **tous en anglais**, y compris celui de la question 2, qui recule pourtant en anglais ;
  - explication restante : la **sensibilité à la formulation**. Le fil de la question 2 demande combien de temps prend l'activation d'OAuth ; la version anglaise de la question insiste sur le blocage (`blocked`, `prevent`) et s'en éloigne. Même sens, autre formulation : jusqu'à **12 rangs** d'écart ;
  - plafond d'une recherche multi-formulation : en gardant le meilleur des deux rangs, on obtiendrait 1, 4, 2, 2, 18, 1, 1, 2, 9, 17, soit un Hit@10 de **80 %**. C'est un plafond optimiste : une vraie fusion (RRF) ne retombe pas toujours sur le meilleur rang.
- **Observation sur la question 10** : son fil dit « *the jwt is signed by [HOST], not [HOST]* ». Les deux noms d'hôte, qui indiquaient les environnements Ping de production et de préproduction, sont devenus deux `[HOST]` identiques : l'anonymisation a effacé exactement l'information qui distingue ce fil.
- **Leçons** :
  - `bge-m3` gère bien le mélange français-anglais en moyenne : traduire systématiquement la question n'est pas une amélioration ;
  - **lire les rangs question par question** : des moyennes identiques peuvent cacher des mouvements forts qui se compensent ;
  - **notre banc est bruité** : avec une seule formulation par langue, un écart de quelques rangs entre deux configurations peut n'être que du bruit de formulation. Il faut mesurer ce bruit (reformulations dans la même langue) avant de juger une amélioration fine ;
  - les reformulations doivent être écrites **sans connaître les fils de référence** (par exemple par un LLM qui ne voit que la question d'origine), sinon leur vocabulaire s'y glisse ;
  - une anonymisation qui remplace des valeurs différentes par le même masque peut effacer l'information qui rend un document pertinent.

### E15 — Recherche par mots-clés classée par fil : `KeywordSearchService` (2026-11-19)

- **Constat** : E13 a montré en SQL qu'une recherche par mots-clés ne bat pas la recherche dense sur le jeu actuel, faute de termes rares. Mais la technique hybride reste à apprendre, et la fusion (RRF) a besoin d'un second classement de **fils**.
- **Hypothèse** : le score de E13 (somme des IDF des mots trouvés), appliqué au meilleur chunk de chaque fil, peut être écrit en **une seule requête**, testée sur un vrai PostgreSQL, et rester déterministe pour que la fusion puisse s'appuyer dessus.
- **Changement** (aucune mesure du banc : la recherche n'est pas encore branchée) :
  - `KeywordSearchService.searchByKeywords(keywords, limit)` retourne des identifiants de fils classés ;
  - développée en TDD, avec `KeywordSearchServiceTest` sur **Testcontainers** (image `pgvector/pgvector:pg18-trixie`, tables créées dans le test, sans la base de l'application), organisée en ZOMBIES (zéro, un, plusieurs mots-clés ; limites ; erreurs) ;
  - le `README` décrit le comportement, la Javadoc du service décrit la requête.
- **La requête, étape par étape** (un bloc `WITH` par étape) :
  1. `keywords` : Java découpe les mots-clés (minuscules, coupure sur tout ce qui n'est ni lettre ni chiffre Unicode, dédoublonnage des mots obtenus, voir E16) et les joint par des espaces. `string_to_array(:keywords, ' ')` puis `unnest` donnent une ligne par mot. Un seul paramètre texte suffit : aucun mot ne contient d'espace ;
  2. `corpus` : `count(*)` des chunks, le « N » de l'IDF ;
  3. `word_idf` : `ts_stat` parcourt tous les `tsvector` de la base et renvoie, pour chaque mot, `ndoc`, le nombre de chunks qui le contiennent. On ne garde que les mots-clés et on calcule `ln(N / ndoc)`. Un mot-clé absent de la base disparaît ici : il ne peut ni matcher ni compter ;
  4. `chunk_scores` : chaque chunk est joint aux mots-clés qu'il contient (`to_tsvector('simple', content) @@ plainto_tsquery('simple', mot)`). La jointure remplace le OU : un chunk sans aucun mot-clé n'a aucune ligne. Le `GROUP BY d.id` additionne les IDF des mots d'un même chunk ; le filtre `responderReplyCount > 0` est ici ;
  5. requête finale : `GROUP BY thread_id` garde le **meilleur chunk** de chaque fil (`max(score)`), puis `ORDER BY` score décroissant, date décroissante, `thread_id` croissant, et enfin `LIMIT`. Le `LIMIT` s'applique **après** le regroupement : il compte des fils.
- **Décisions de conception** :
  - **meilleur chunk plutôt que somme des chunks** : un fil découpé en dix chunks ne doit pas gagner parce qu'il est long. Comparer plus tard avec les mots distincts du fil entier ;
  - **IDF en `numeric`** : même problème que pour la RRF. L'addition de `double` dépend de l'ordre, et deux fils mathématiquement à égalité pouvaient différer d'un bit, rendant le départage par la date inopérant. `numeric` est exact pour l'addition ;
  - **date puis `threadId`** pour départager : l'ordre ne doit jamais dépendre de l'ordre physique des lignes en base ;
  - **un mot présent partout vaut 0** (`ln(N/N)`) : il ne distingue rien mais reste un résultat valable, donc on ne filtre pas sur `score > 0` ;
  - **paramètres liés** : les mots ne sont jamais concaténés au SQL ; le seul texte SQL fourni à `ts_stat` est une constante.
- **Pièges rencontrés** :
  - `JdbcClient.sql(...)` ne fait **rien** sans terminaison (`.update()`, `.query(...).list()`) : les premiers tests passaient alors que la table n'existait pas ;
  - un paramètre nommé ne peut remplacer qu'une valeur entière, jamais un morceau d'un littéral : `'{"threadId": :threadId}'` n'est pas substitué. On construit le JSON avec `json_build_object` ;
  - les antislashs d'une regex Java s'écrivent `\\p{L}` dans le code, pas `\\\\p{L}` : avec quatre, la classe exclut les lettres `p`, `L`, `N` et efface tous les mots ;
  - un `distinct()` en Java **après** un `LIMIT` SQL donne moins de résultats que demandé : le dédoublonnage doit précéder la limite, donc se faire dans la requête ;
  - un test qui passe d'emblée ne prouve rien : il faut voir chaque test **rouge pour la bonne raison** avant d'écrire le code (le test « plusieurs mots-clés sur le même chunk » ne peut pas être rouge, c'est un test de caractérisation).
- **Limites connues** :
  - `ts_stat` recalcule les `tsvector` de **tout** le corpus à chaque appel, et la recherche calcule `to_tsvector` sur chaque chunk : acceptable pour un apprentissage, à remplacer par une colonne `tsvector` indexée (GIN) et des IDF précalculés ;
  - dans la première version, les mots composés étaient écrasés (`api-key` devenait `apikey` et ne matchait plus rien, sans erreur) : corrigé en E16 ;
  - l'IDF mesure la rareté, pas la pertinence (E13).
- **Leçons** :
  - une recherche secondaire doit rendre un classement **déterministe** : la fusion et les tests en dépendent ;
  - un classement par fil se pense en deux niveaux (score du chunk, puis agrégation par fil), et la limite se place après le second ;
  - tester le SQL sur la vraie base (Testcontainers) évite de simuler ce qui fait justement la valeur de la requête.

### E16 — Extraire les mots-clés d'une question : qui nettoie quoi (2026-11-20, en cours)

- **Constat** : E13 a montré deux défauts du LLM : des mots composés (`api-key`) malgré la consigne « un seul mot », et des mots qui tombent sur les masques d'anonymisation (`ip`, `host`, `uuid` correspondent à `[IP]`, `[HOST]`, `[UUID]`). Avec `KeywordSearchService`, un composé devenait `apikey` et ne matchait plus rien, sans erreur.
- **Hypothèse** : une règle écrite dans le prompt n'est jamais garantie (le LLM a déjà ignoré « un seul mot ») ; ce qui doit être fiable se règle **en code**, dans des fonctions pures testables sans LLM.
- **Changement** :
  - **prompt** (`keyword-extraction.st`) : deux règles ajoutées (ne pas produire `ip`, `host`, `uuid`, `redacted`, et découper les composés) avec un exemple neutre, `rate-limit`, qui ne vient pas du jeu d'évaluation. Elles économisent des places dans les 3 à 8 mots, sans rien garantir ;
  - **`KeywordSearchService`** : le découpage des composés se fait ici, pas dans l'extracteur. La base découpe elle-même le texte indexé ; découper les mots-clés de la même façon est de la tokenisation du moteur, et un seul endroit doit la connaître, quel que soit l'appelant. Le service coupe sur tout ce qui n'est ni lettre ni chiffre, puis dédoublonne **les mots** ;
  - **`KeywordExtractor.normalize`** (fonction pure) : ignore les éléments vides ou `null`, écarte les mots de masque (comparés sans tenir compte de la casse), plafonne à 8 mots. Un mot écarté ne compte pas dans le plafond ;
  - sous-package `retrieval/keyword/` pour les deux classes de la recherche par mots-clés.
- **Mesure** : pas encore, l'appel au LLM (`extract`) n'est pas écrit. À faire ensuite avec `KeywordExtractionExplorationTest` : les mots obtenus sont-ils des mots simples, sans masque, en 3 à 8 ?
- **Ce qui s'est passé en chemin** :
  - la première répartition (découpage dans l'extracteur, nettoyage dans le service) dupliquait des règles et se contredisait (le service supprimait la ponctuation, l'extracteur la découpait). Voir où deux classes font la même chose est le signe qu'une responsabilité est mal placée ;
  - on avait dit que le nettoyage du service protégeait de l'injection SQL. C'était faux : les mots sont des paramètres liés. Il sert à rendre les mots **comparables** à ceux de `ts_stat` ;
  - `distinct()` avant le découpage laissait passer un mot en double (`["api-key", "key"]` donne `key` deux fois), qui comptait deux fois dans le score. Le dédoublonnage doit porter sur les mots finaux, pas sur les éléments d'entrée ;
  - séparer l'appel au LLM d'une fonction pure `normalize` permet de tester la politique sans simulacre de `ChatClient`.
- **Leçons** :
  - **ne pas faire confiance à la sortie d'un LLM pour une règle qui doit être respectée** : la vérifier en code ;
  - **isoler la partie non déterministe** (l'appel au modèle) derrière des fonctions pures testables ;
  - la tokenisation d'une requête doit **imiter celle de l'index** ;
  - dédoublonner à la fin d'une chaîne de transformations, pas au début.

---

## 3. Historique des mesures

| Date | Configuration | Hit@5 | Hit@10 | MRR |
|------|---------------|-------|--------|-----|
| 2026-11-15 | `bge-m3-8k`, chunk 1500, nettoyage + anonymisation, filtre `humanReplyCount > 0`, top 10 | 60 % | 70 % | 0,383 |
| 2026-11-15 | + filtre `responderReplyCount > 0`, nettoyage avant anonymisation, `[HOST]` et `[IP]` (E12) | 60 % | 70 % | 0,457 |
| 2026-11-15 | inchangé : recherche par mots-clés explorée en SQL, non intégrée (E13) | 60 % | 70 % | 0,457 |
| 2026-11-17 | même configuration, questions traduites en anglais (E14, colonne EN) | 60 % | 70 % | 0,453 |
| 2026-11-19 | inchangé : recherche par mots-clés classée par fil, non branchée sur le banc (E15) | 60 % | 70 % | 0,453 |
| 2026-11-20 | inchangé : nettoyage des mots-clés extraits, non branché sur le banc (E16) | 60 % | 70 % | 0,453 |

---

## 4. Points d'attention pour un RAG en production

### Données et ingestion

- **Comprendre la source avant de la vectoriser.** Échantillonner, mesurer les longueurs, repérer les gabarits répétés. Chaque problème trouvé ici l'a été en lisant les données.
- **Tout le texte n'est pas bon à indexer** : sommaires, gabarits, annonces, demandes sans réponse. Les marquer en métadonnée plutôt que les supprimer.
- **Nettoyer avant de découper**, puisque le découpeur compte des tokens.
- **Choisir l'unité de document selon le sens** : un fil Slack, une section, un ticket. Pas une page, ni une taille fixe arbitraire.
- **Anonymiser en dernier** : aucune transformation du texte (nettoyage, normalisation) ne doit avoir lieu après l'anonymisation, sous peine de recomposer une donnée que les règles n'avaient pas reconnue.
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
- **Les embeddings sont faibles sur les termes exacts et rares** (identifiants, noms d'application, codes d'erreur peu fréquents) : la recherche hybride (vecteurs + mots-clés) est le remède classique. Elle n'aide pas quand les termes sont du vocabulaire courant du domaine (E13).
- **Vérifier si la référence est trouvée mais mal classée** (top 30) ou pas trouvée du tout : le premier cas relève du reranking, le second de la recherche.
- **Se méfier de l'expansion de requête par synonymes** : chaque mot ajouté peut faire passer des documents concurrents devant.

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

1. **Mesurer le bruit de formulation** : ajouter une ou deux reformulations françaises par question, écrites sans connaître les fils de référence (par exemple par un LLM), et observer la variation des rangs (E14). Permet de savoir quel écart est significatif avant de juger les expériences suivantes.
2. **Reranking** : récupérer 30 candidats par la recherche dense, puis les faire reclasser par un modèle qui lit la question et chaque fil ensemble (cross-encoder ou LLM). Cible : les questions 5, 9 et 10, dont les références sont dans le top 30 dense mais mal classées (E13).
3. **Recherche hybride** : en cours sur la branche `feature/hybrid-search`, pour apprendre la technique (fusion RRF, recherche par mots-clés classée par fil et nettoyage des mots-clés extraits faits, E15 et E16 ; restent l'appel au LLM de l'extracteur, l'assemblage et la comparaison dense / hybride). Le banc actuel ne devrait pas montrer de gain (E13) : il faut d'abord ajouter au jeu d'évaluation des questions à **terme rare** (identifiant, nom d'application, code peu fréquent). Pistes de E13 : mots groupés par concept (OU dans le concept, ET entre concepts), vrai BM25, fusion RRF. Les fichiers d'exploration (`prompts/keyword-extraction.st`, `KeywordExtractionExplorationTest`) servent de point de départ.
4. **Expansion de requête** : faire reformuler la question en plusieurs variantes par un LLM (`MultiQueryExpander` de Spring AI), chercher avec chacune, puis fusionner. Cible : le décalage de vocabulaire (E7) et la sensibilité à la formulation (E14, plafond optimiste de 80 % de Hit@10). Attention à l'effet observé en E13 avec les synonymes.
5. **Résumé structuré à l'ingestion** : faire réécrire chaque fil en *Problème / Cause / Solution*, et vectoriser ce résumé. Coût : un appel LLM par fil.
6. **Ajuster la consigne sur les dates** (E10).
7. **Calibrer un seuil de similarité** pour les questions hors sujet, avec `bge-m3`.
8. **Évaluer la génération**, et pas seulement la recherche : fidélité au contexte, sources correctes, par exemple avec un LLM juge.
9. **Industrialiser** : tests d'intégration autonomes (Testcontainers), schéma géré par Liquibase, refactoring en architecture hexagonale.
10. **Anonymisation plus fine** : garder une partie utile et non sensible des valeurs masquées (`[HOST:prep]`, `[HOST:prod]`), pour ne pas perdre l'environnement concerné (E13).
11. **Nettoyage** : un emoji collé à un mot (`:question:Submitted`) n'est pas retiré.

---

## 6. Glossaire

| Terme | Définition |
|-------|------------|
| **Chunk** | Morceau de document vectorisé et stocké comme une ligne de la base. |
| **Embedding** | Vecteur qui représente le sens d'un texte : ses coordonnées sur une « carte du sens ». |
| **Top-k** | Nombre de chunks les plus proches renvoyés par la recherche. |
| **Score de similarité** | Proximité entre la question et un chunk (ici 1 − distance cosinus). Relatif au modèle. |
| **Recherche hybride** | Combinaison de la recherche vectorielle (sens) et de la recherche par mots-clés (termes exacts). |
| **IDF** | *Inverse Document Frequency* : ln(nombre de documents / nombre de documents contenant le mot). Mesure la rareté d'un mot, pas sa pertinence. |
| **BM25** | Score de recherche par mots-clés qui combine IDF, fréquence du mot (avec saturation) et normalisation par la longueur du document. |
| **RRF** | *Reciprocal Rank Fusion* : fusion de classements par la somme de 1/(60 + rang). Utilise les rangs, car les scores de deux recherches ne sont pas comparables. |
| **Reranking** | Second classement des candidats par un modèle qui lit la question et chaque document ensemble. |
| **Hit@k** | Part des questions dont un document de référence est dans les k premiers résultats. |
| **MRR** | *Mean Reciprocal Rank* : moyenne de 1/rang du premier document de référence. |
| **Walking skeleton** | Version minimale qui fonctionne de bout en bout, améliorée ensuite étape par étape. |
| **Upsert** | Insertion qui met à jour la ligne si l'identifiant existe déjà. |
