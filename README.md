# Suivi nutri

Appli Android perso pour tenir des réflexes alimentaires, pas pour compter les calories.
Info, jamais verdict : une question sans réponse est un NA, pas un échec.

## Installer

Chaque push sur `main` construit un APK signé et le publie dans les
[Releases](../../releases). Lien toujours à jour, à garder en favori sur le téléphone :
https://github.com/dash52000/Suivi_nutri/releases/latest/download/suivi-nutri.apk

L'installer **par-dessus** la version existante, sans désinstaller.

## Signature

Le workflow signe avec une clé fixe, fournie par deux secrets du dépôt
(Settings > Secrets and variables > Actions) :

- `KEYSTORE_BASE64` : le fichier `.jks` encodé en base64
- `KEYSTORE_PASSWORD` : le mot de passe du magasin et de la clé (alias `suivinutri`)

La clé ne doit jamais être commitée. Sans elle, impossible de publier une mise à
jour qui s'installe par-dessus l'appli existante.

## Labo

- **Tendance du poids** : filtre et lisseur de Kalman (niveau local + pente, pas journalier).
  Bruit de pesée (0,6 kg)² le matin, (1,2 kg)² l'après-midi ; bruit du niveau (0,05 kg/j)²,
  de la pente (0,005 kg/j par jour)². Pesée écartée si elle tombe à plus de 4 écarts-types de
  la prévision. Le lisseur (Rauch-Tung-Striebel) n'a pas de retard et donne une bande d'incertitude.
- **Correction par l'eau** (v1.7, quand la balance mesure l'eau) : l'eau a sa propre tendance de Kalman
  (bruit (0,5 kg)², raide). L'écart du jour à cette tendance explique 52 % des variations quotidiennes
  du poids (analyse R du 10/11/2026) : pesée corrigée = poids − 0,73 × écart, bruit divisé par deux
  ((0,42 kg)² le matin) ; masse grasse corrigée = masse grasse + 0,8 × écart (bruit (0,5 kg)²),
  masse maigre = poids − masse grasse corrigée (bruit (0,4 kg)²). Sans mesure d'eau : valeurs brutes.
- **Composition** (balance Withings via Health Connect, pesée Withings préférée aux saisies manuelles) :
  masse grasse = poids × taux de graisse, masse maigre = poids − masse grasse, chacune lissée par son
  propre Kalman (bruit de mesure (0,7 kg)² et (0,77 kg)² sans correction par l'eau, graisse peu souple,
  maigre souple : elle porte l'eau et le glycogène).
- **Apports estimés avec la composition** (dès 3 mesures dans la semaine) : dépense moyenne +
  (9 440 × Δ masse grasse + 1 816 × Δ masse maigre) ÷ jours, densités de Kevin Hall (2008) ; variance =
  a² + b² + 2ρab (a, b : écarts-types des deux termes). Les erreurs de la balance sur la graisse et le
  maigre sont corrélées : ρ = −0,75 brutes, −0,15 corrigées par l'eau (au prorata des mesures corrigées
  de la semaine). En simulation avec de l'eau transitoire et une bio-impédance bruitée, l'erreur passe
  d'environ 106 à 46-70 kcal/j.
- **Apports estimés sans composition** : dépense moyenne + Δ niveau lissé (lundi matin → lundi matin) × 7 700 ÷ 7,
  avec IC 95 % tiré de la covariance a posteriori des deux niveaux (covariance croisée du lisseur).
  Semaine complète : ≥ 3 pesées retenues et ≥ 5 jours de dépense. Semaine en cours dès 2 jours écoulés.
- **Affichage des apports** (v1.8) : une barre par semaine complète, puis les 7 derniers jours en creux
  (pesée d'il y a 7 jours → pesée de ce matin, dépense des 7 jours précédents) à la place de la semaine en
  cours ; le texte sous le graphe donne les 7 derniers jours.
- **Pesée du jour décomposée** (v1.8) : écart à la tendance = part de l'eau (0,73 × écart de l'eau à sa
  tendance) + reste ; dans l'onglet Données (chaque jour) et sous le graphe du poids, qui montre aussi
  les pesées corrigées reliées aux brutes. Masqué avec l'option « masquer la pesée du jour ».
- **Jours sans montre** (v1.7) : sans sommeil ni séance, une dépense sous 2 000 kcal n'est que le
  métabolisme de base compté par Garmin (1 565 ou 1 675 kcal) ; elle est écartée, comme toute dépense
  sous 800 kcal.
  Niveau absolu probablement surestimé (dépense de la montre) : seules les variations comptent.
- **Mécanismes des réflexes** (le jour même, sans bruit de pesée) : pris ton temps → rab, boîtes
  posées → rab, plaisir baissé → rab, collation → fringale en séance, pas de rab → faim au coucher ;
  différence de proportions (Agresti-Caffo), soirs francs seulement (« à moitié » exclu).
- **Lendemain** : régression jointe de l'écart pesée − tendance à J+1 (J+2, J+3 pour la décroissance)
  sur toutes les expositions du soir J à la fois : rab et dessert sur 3 niveaux (0 ; 0,5 ; 1), alcool
  en unités, beaux-parents, longue sortie (endurance ≥ 1 h 15). Une exposition n'entre que si elle a ≥ 5 jours exposés et ≥ 5 non exposés parmi
  les jours complets ; ≥ 15 jours complets. Erreurs-types de Newey-West (retard 2 jours, jours voisins seulement).
- **Plusieurs semaines** : pente de régression des apports estimés sur le taux de chaque réflexe,
  un réflexe à la fois, Newey-West (retard 1 semaine). Au moins 8 semaines.
- **Effets croisés** : différence de proportions, intervalle d'Agresti-Caffo.
  Nuit courte et alcool la veille → rab / dessert ; tupp remplis la veille → tupp mangé le midi ;
  séance le midi → petit creux ; petit creux → rab le soir.
- **Tests multiples** : correction de Benjamini-Hochberg sur l'ensemble des effets ; un effet n'est
  présenté comme net (en couleur) que si q < 5 %.

Les tests unitaires (`app/src/test`) comparent ces calculs à des références : numpy (Kalman, vérifié
contre la loi a posteriori exacte), statsmodels (HAC), scipy (lois de Student et normale, BH).

## Export pour l'analyse R

Réglages > Exporter pour R : une ligne par jour (`read.csv`), avec pesée, heure et source, pesée écartée,
tendance et son écart-type, pente (kg/semaine), résidu, dépense, sommeil, minutes de séance, séance du
midi, longue sortie, composition (taux de graisse, masses grasse et maigre, masse maigre Health Connect,
eau, os) et leurs tendances, toutes les réponses, nombre de boissons et unités d'alcool. Depuis la v1.7 :
dépense retenue (`kcal_retenue`, NA les jours sans montre), tendance et écart de l'eau, poids et masse
grasse corrigés de l'eau (`tendance_kg` suit le poids corrigé ; `residu_kg` reste pesée brute − tendance).

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
- **v0.9** : journal d'utilisation local (notifs, ouvertures, réponses horodatées, écrans), exportable
- **v1.5** : tendance de Kalman avec bande d'incertitude, apports avec IC et semaine en cours,
  régression jointe du lendemain (dont les longues sorties), correction de Benjamini-Hochberg,
  pesées pondérées (après-midi, aberrantes, doublons), export journalier complet pour R
- **v1.6** : balance Withings (pesée préférée, composition lue), apports par compartiments graisse / maigre,
  composition dans l'export
- **Avant la v2, obligatoire** : une analyse en R sur l'export (validation des paramètres du Kalman,
  des effets estimés et des réflexes à garder, à partir du journal d'utilisation). La suite n'est
  lancée qu'au vu de ses conclusions. Première analyse faite le 10/11/2026 (paramètres du poids validés).
- **v1.7** : conclusions de l'analyse R — correction par l'eau du poids et de la graisse, corrélation
  graisse / maigre dans l'intervalle des apports, jours sans montre écartés de la dépense, faim au
  coucher et collation en pause par défaut (toujours « non » et toujours « oui »), réactivables
- **v1.8** : apports des 7 derniers jours en dernière barre (même graphe), pesée du jour décomposée en eau et reste,
  pesées corrigées de l'eau dans le graphe du poids, calcul du labo partagé entre Données et Labo
- **v2** : journal alimentaire (scan Open Food Facts, portions CIQUAL) ; refonte du design,
  plus belle et plus pratique
