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
  Les apports de chaque semaine y sont recalculés sans le retard de la tendance
  lissée (≈ 9 jours) : dépense + (moyenne des pesées de la semaine suivante −
  moyenne de la semaine précédente) ÷ 2 × 7 700 ÷ 7, fenêtre centrée sur la semaine.
- Niveau absolu des apports probablement surestimé (dépense de la montre) :
  seules les variations sont interprétées.
- Effets croisés : différence de proportions, intervalle d'Agresti-Caffo.
  Croisements : nuit courte et alcool la veille → rab / dessert ; tupp remplis la veille →
  tupp mangé le midi ; séance le midi → petit creux l'après-midi ; petit creux → rab le soir.
- Minimum : 5 observations par groupe, 8 semaines pour les régressions.

Les tests unitaires (`app/src/test`) comparent ces calculs à des valeurs de
référence obtenues avec scipy et statsmodels ; ils tournent à chaque build.

## Feuille de route

- **v0.1** : squelette, chaîne de build et test de mise à jour sans perte de données
- **v0.2** : lecture Health Connect (poids, dépense, activités, sommeil)
- **v0.3** : questionnaire du soir, alcool en unités, notif, sauvegarde et export CSV
- **v0.4** : tendance du poids, apports estimés, labo
- **v0.5** : questions configurables (pause, questions perso) — fin de la v1
- **v0.6** : bilan des 7 derniers jours, graphes du labo (apports/dépense, calendrier des réflexes,
  forest plots des effets, décroissance J+1 à J+3)
- **v0.7** : question « pris ton temps », analyse hebdo alignée, option « masquer la pesée du jour »
- **v0.8** : section Midi (6 questions), questionnaire par moment de la journée, réflexes et effets croisés du midi dans le labo
- **v2** : journal alimentaire (scan Open Food Facts, portions CIQUAL) ; refonte du design,
  plus belle et plus pratique
