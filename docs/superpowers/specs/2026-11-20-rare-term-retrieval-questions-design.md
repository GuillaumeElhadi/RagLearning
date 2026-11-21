# Questions de retrieval à termes rares

## Objectif

Ajouter dix questions au jeu d'évaluation du retrieval afin de vérifier qu'une requête contenant un terme technique ou un nom propre peu fréquent retrouve le fil Slack pertinent.

## Périmètre et format

- Conserver sans modification les dix entrées existantes de `src/test/resources/evaluation/retrieval-questions.json`.
- Ajouter dix entrées conformes au schéma existant : variantes `fr` et `en`, plus `expectedThreadIds`.
- Chaque question doit être fondée sur une demande réelle de `sources/slack_export.json` et référencer au moins un fil qui contient une réponse humaine pertinente.
- Préserver dans les questions les termes discriminants utiles au test, par exemple un identifiant de paramètre, un en-tête HTTP, un nom de produit ou une technologie.
- Éviter les doublons avec les questions existantes et ne pas recopier de données sensibles ou propres à un cas individuel lorsqu'elles ne sont pas nécessaires à la question.

## Validation

Le JSON doit rester valide, contenir vingt entrées, et chaque nouvelle entrée doit comporter les deux langues et un ou plusieurs identifiants de thread. Exécuter le test d'évaluation/retrieval ciblé disponible dans le projet pour vérifier la compatibilité avec le chargement actuel du jeu.

## Hors périmètre

Ne pas modifier l'indexation, le modèle de retrieval, le format du jeu d'évaluation ni les questions déjà présentes.
