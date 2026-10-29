# Suivi nutri

Appli Android perso pour tenir des réflexes alimentaires, pas pour compter les calories.
Info, jamais verdict : une question sans réponse est un NA, pas un échec.

## Installer

Chaque push sur `main` construit un APK signé et le publie dans les
[Releases](../../releases). Sur le téléphone : télécharger le dernier APK et
l'installer **par-dessus** la version existante, sans désinstaller.

## Signature

Le workflow signe avec une clé fixe, fournie par deux secrets du dépôt
(Settings > Secrets and variables > Actions) :

- `KEYSTORE_BASE64` : le fichier `.jks` encodé en base64
- `KEYSTORE_PASSWORD` : le mot de passe du magasin et de la clé (alias `suivinutri`)

La clé ne doit jamais être commitée. Sans elle, impossible de publier une mise à
jour qui s'installe par-dessus l'appli existante.

## Labo

- Tendance du poids : lissage exponentiel (α = 0,1 par pesée).
- Apports estimés par semaine : dépense moyenne + Δ tendance × 7 700 kcal/kg ÷ 7.
- Effet lendemain : différence de moyennes, intervalle de Welch.
- Effets sur plusieurs semaines : pente de régression, un réflexe à la fois,
  erreur-type de Newey-West (retard 1, seules les semaines voisines comptent).
- Effets croisés : différence de proportions, intervalle d'Agresti-Caffo.
- Minimum : 5 observations par groupe, 8 semaines pour les régressions.

Les tests unitaires (`app/src/test`) comparent ces calculs à des valeurs de
référence obtenues avec scipy et statsmodels ; ils tournent à chaque build.

## Feuille de route

- **v0.1** : squelette, chaîne de build et test de mise à jour sans perte de données
- **v0.2** : lecture Health Connect (poids, dépense, activités, sommeil)
- **v0.3** : questionnaire du soir, alcool en unités, notif, sauvegarde et export CSV
- **v0.4** : tendance du poids, apports estimés, labo
- **v0.5** : questions configurables (pause, questions perso) — fin de la v1
- **v2** : journal alimentaire (scan Open Food Facts, portions CIQUAL)
