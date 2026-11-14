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

## Objectif de perte (onglet Cap)

Le but de l'appli est de perdre du poids : elle garde ses mesures et ajoute un cap et une boucle hebdomadaire.
Philosophie, état et feuille de route détaillés : doc de projet `suivi-nutri.md`.

- **Couloir d'effort** (v1.11) : on juge l'effort (de combien je mange moins qu'au départ, kcal/j), pas la pente.
  Effort = ρ × variation de la tendance ÷ jours + ε × écart moyen au niveau de départ (modèle de Hall linéarisé,
  ρ = 8 840 kcal/kg, ε = 25,8 kcal/j/kg, Sanghvi 2015), sur 2 semaines glissantes. Couloir −505 à −316 kcal/j
  (l'ancien −0,40 à −0,25 kg/sem traduit par Hall) : la perte attendue ralentit d'elle-même, ce n'est plus compté
  comme un relâchement. Au-dessus : P(effort > −316) ≥ 90 % ; plus vite : IC 95 % sous −505 ; dans le couloir :
  estimation entre les deux ; sinon (ou moins de 3 pesées) pas lisible. Le graphe montre la bande de poids attendue.
- **Semaine du dimanche** (v1.11) : comportements du dimanche au samedi, poids du dimanche matin au dimanche matin.
  Verdicts **figés** avec les seules pesées connues à la clôture (sinon le lisseur les révisait après coup).
- **Rodage** (v1.11) : les 2 premières semaines ne sont pas jugées. Avec des verdicts figés, la tendance met environ
  3 semaines à suivre un changement d'effort (−86, −196, −292 puis −353 kcal/j estimés pour un vrai −400).
- **Simulation dans le build** (`SimulationCouloirTest`) : pesées + eau + bruit réel, 26 semaines. Effort tenu à −400 :
  au plus 20 % de propositions de baisse (≈ 10 %) ; effort à −250 : au moins 80 % ; appétit qui compense : au moins 90 %.
- **Budget bière** : 3 bouteilles de 33 cl par semaine au départ (réglable). Depuis la v1.12, le bilan du dimanche
  **propose** −1 après 2 semaines de suite au-dessus du couloir, +1 après 2 semaines dans le couloir (sans dépasser le
  départ) ; rien ne change sans « Accepter ». Après une réponse, il faut 2 nouvelles semaines. Bières seulement (pils à
  quadrupel), en équivalent 33 cl ; « oui » sans boisson saisie = NA.
- **Bilan du dimanche** (v1.12, en haut du Cap, notif le dimanche à 19 h ; les bilans passés se relisent) :
  1. le cap (effort, verdict, chemin depuis le départ face à la bande de Hall) ; 2. la balance de la semaine (pesées qui
  sortent du lot, part de l'eau, ce qui s'est passé la veille et son effet habituel) ; 3. graisse et maigre sur 4
  semaines ; 4. protection du muscle (renfos, protéines en g/kg/j, rythme) ; 5. effort déclaré (questionnaire en kcal/j
  face aux habitudes des 4 semaines avant le départ : rab ≈ 300, dessert ≈ 250, alcool ≈ 70 kcal/unité × 1,5 pour la
  bière, pas ≈ 0,045 kcal) et, toutes les 4 semaines à partir de la 8e, face-à-face avec l'effort mesuré ; 6. ce qu'on
  a appris (liens passés à probable ou solide) ; 7. pour la semaine qui vient : bilan du levier écoulé face aux leviers
  proposés non choisis (différence de différences), leviers proposés avec leur gain chiffré (kcal/semaine et kg en un
  mois, modèle de Hall), proposition de budget, soir à surveiller, plan du week-end.
- **Levier de la semaine** : 3 propositions, les réflexes les moins tenus des 14 derniers jours (au moins 3
  réponses), ou un autre au choix. Adhérence = soirs tenus / soirs renseignés. Pas de conclusion causale dans
  l'appli : R agrège les semaines (`levier_semaine`).
- **Plan du week-end** (vendredi → dimanche) : 5 repas normal / dehors, bières prévues par jour. Du lundi au jeudi,
  comparaison avec le questionnaire (midi à l'extérieur, dîner chez les beaux-parents ou ailleurs, boissons).
- **Signal de la semaine** (jusqu'à la v1.11, remplacé par la notif du bilan) : une notif au plus par semaine, à 18 h, le soir qui dérape le plus sur 8 semaines
  (rab, part normale de dessert ou ≥ 2 unités d'alcool ; au moins 3 soirs renseignés et 50 % de dérapages) ;
  vendredi par défaut, pour le plan du week-end. Désactivable.
- **Pas hors sport** (v1.10) : pas Garmin du jour moins les pas faits pendant les séances (une tranche à
  cheval sur une séance est coupée au prorata ; séances qui se chevauchent comptées une fois). Depuis la v1.11, une
  marche enregistrée sur la montre n'est pas une séance : ses pas comptent. Lecture limitée à la période utile
  (8 semaines avant le départ), et une erreur de lecture est notée et affichée. Jours finis avec
  au moins 1 000 pas seulement. Base = médiane des 8 semaines avant le départ (à défaut, des 8 premières semaines
  de données, au moins 14 jours) ; cible = base + 2 000. Chaque semaine : moyenne et jours au-dessus de la cible ;
  levier possible « pas hors sport ≥ cible ». Pas lus par quarts d'heure agrégés par Health Connect (Garmin
  seulement) : Garmin écrit des enregistrements de durée nulle que la bibliothèque refuse en lecture brute.
  Données affiche les pas d'hier, total et hors sport. Autorisation Health Connect facultative.
- **Protéines** (v1.11) : whey (25 g la dose), œufs (6 g), viande ou poisson (25 g la portion de 100-120 g),
  saisis le soir ; moyenne de la semaine en g/j et g/kg. Laitages et légumineuses non comptés.
- **Activité** : chaque semaine affiche séances (hors marches), minutes et dépense moyenne (jours avec montre). Flux unique
  Suivi_nutri → coach running par l'export CSV ; pas de lecture du plan d'entraînement.

## Labo

Depuis la v1.14, l'écran se lit en quatre temps : **Ce qu'on sait** (les liens probables ou solides, toutes
sources confondues), puis trois questions : qu'est-ce qui fait bouger la balance le lendemain (effets de la veille,
semaine type, d'où vient l'écart du jour), les réflexes marchent-ils et qu'est-ce qui pousse au rab (calendrier,
mécanismes, effets croisés, soirs de la semaine), où en est la graisse (poids, graisse et maigre, apports). Les
explications sont repliées derrière « ? ».

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
- **Apports estimés sans composition** : dépense moyenne + Δ niveau lissé (dimanche matin → dimanche matin, comme
  le Cap) × ρ ÷ 7, avec ρ = 8 840 kcal/kg (Hall, v1.13 ; la dépense est mesurée, donc le terme ε y est déjà),
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
- **Mécanismes des réflexes** (v1.12, le soir même, sans bruit de pesée) : pris ton temps, boîtes posées, plaisir
  baissé, assiette ½ ¼ ¼ → rab. Probabilités bayésiennes : par lieu du dîner, loi bêta a priori centrée sur la
  fréquence commune du rab, de force 4 soirs (sceptique), écart pondéré comme Mantel-Haenszel, 4 000 tirages à graine
  fixe. Niveaux : piste ≥ 70 % (3 soirs par groupe), probable ≥ 90 % (5), solide ≥ 97,5 % (8). Simulation : sans effet,
  « probable » sort dans 6 à 9 % des cas, « solide » dans environ 2 % ; avec un effet fort (20 % contre 60 % de rab),
  « probable » 9 fois sur 10 à 20 soirs par groupe (`MecanismesTest`). Soirs francs seulement.
- **Lendemain** : régression jointe de l'écart pesée − tendance à J+1 (J+2, J+3 pour la décroissance)
  sur toutes les expositions du soir J à la fois : rab et dessert sur 3 niveaux (0 ; 0,5 ; 1), alcool
  en unités, beaux-parents, longue sortie (endurance ≥ 1 h 15). Une exposition n'entre que si elle a ≥ 5 jours exposés et ≥ 5 non exposés parmi
  les jours complets ; ≥ 15 jours complets. Erreurs-types de Newey-West (retard 2 jours, jours voisins seulement).
  Depuis la v1.14, a posteriori avec un a priori sceptique N(0, τ²), τ = 0,15 kg pour un oui franc et 0,03 kg par
  unité d'alcool (loi de Student), et niveau de preuve « ça fait monter ». Simulation (`SimulationLendemainTest`) :
  sans effet, « probable » dans 3 à 12 % des cas, « solide » dans 0 à 5 % ; +0,1 kg par unité d'alcool : « probable »
  68 % à 6 semaines, 93 % à 12. Avec τ = 0,1 kg par unité, l'alcool sortait « probable » 17 % du temps sans effet.
- **Effets croisés** (v1.14 : même moteur bêta-binomial que les réflexes, à lieu de dîner égal pour le rab et le
  dessert) : nuit courte et alcool la veille → rab / dessert ; jour de séance → rab / dessert (compensation après
  le sport) ; tupp remplis la veille → tupp mangé le midi ; séance le midi → petit creux ; petit creux → rab.
  Simulation : séance → rab sans effet, alors que séances et beaux-parents tombent le dimanche : « probable » 2 à 8 %.
  Ceux qui reposent sur une question en pause ne sont pas affichés.
- **Annonce** (v1.14, plus de Benjamini-Hochberg) : un lien est annoncé (Ce qu'on sait, bilan du dimanche) s'il est
  probable dans le sens attendu, ou solide dans le sens inverse. Une quarantaine de sens sont suivis : un
  « probable » peut être un faux positif ; vrai, il finit solide, sinon il redescend.
- **Semaine type** (v1.14, 12 dernières semaines) : écart moyen de la pesée à la tendance par jour (IC de Student,
  ≥ 3 pesées), et part des soirs avec rab, alcool, dessert, beaux-parents par jour.
- **D'où vient l'écart du jour** (v1.14, 4 dernières semaines, ≥ 14 pesées) : somme des carrés de l'écart découpée
  dans l'ordre veille (effets a posteriori × expositions, centrés) → eau du jour non expliquée → reste. Simulation :
  sans effet, la veille « explique » 4 à 7 % ; avec effets, environ 15 % ; l'eau environ un tiers.
- **Graisse et maigre dans le temps** (v1.14) : tendances corrigées de l'eau en variation sur 90 jours, bandes à 95 %.
- **Cascade** (v1.15, depuis le départ et sur les 4 dernières semaines, dès 4 semaines) : effort mesuré (Hall) découpé
  en rab, dessert, alcool (coûts de table, par rapport aux 4 semaines avant le départ), activité (dépense de la
  montre moins 10 kcal/j par kg d'écart de poids, par rapport aux 12 semaines avant le départ) et non expliqué.
  Moyennes jour de semaine par jour de semaine ; marge de chaque barre = incertitude de l'habitude d'avant le départ
  (± 10 % de l'activité pour la montre, incertitude de sa sensibilité au poids) ; marge du non-expliqué = mesuré et
  barres réunis. Simulation (`SimulationCascadeTest`, 12 jours de questionnaire avant le départ) : total ±65 kcal/j à
  3 mois, ±40 à 6 ; marges rab ±82, dessert ±63, alcool ±22, activité ±80 ; non expliqué ±150, « lisible » à tort
  0 à 13 %, portions −150 kcal/j non déclarées vues une fois sur deux. Un facteur personnel sur les coûts ne
  s'apprend pas (±0,3 au bout d'un an, mélangé au non-déclaré) : abandonné. Remplace le face-à-face du bilan.

### Pesée et selle (v1.16)

Question du soir, section Matin : « Pesée de ce matin, par rapport à la selle ? » avant / après / pas de selle ce
matin. Référence = avant (l'habitude). Une pesée après la selle, ou un matin sans selle, est corrigée avant le Kalman
(sa variance ajoutée à celle de la pesée) ; le « maigre » aussi. Écart estimé par régression bayésienne sur les écarts à
la tendance (deux passes), à soir copieux de la veille égal ; soir copieux = rab, dessert ou beaux-parents (déduit des
réponses, rien à saisir). A priori pour corriger : après = N(−0,15 ; 0,10²) kg ; sans selle et effet croisé
après × copieux : N(0 ; 0,15²) et N(0 ; 0,10²). Niveaux de preuve avec un a priori sceptique centré sur zéro.
Labo : bloc « Pesée et selle » sous la balance du lendemain ; liens dans « Ce qu'on sait » et le bilan. Simulation
(`SimulationSelleTest`) : avec 5 % de matins après, rien ne change (erreur de la tendance 0,135 kg avec ou sans) ;
avec 40 %, 0,137 contre 0,160 kg ; l'effet croisé (−0,1 kg) reste illisible (3 à 10 % « probable »).

Les tests unitaires (`app/src/test`) comparent ces calculs à des références : numpy (Kalman, vérifié
contre la loi a posteriori exacte), statsmodels (HAC), scipy (lois de Student et normale, BH).

## Export pour l'analyse R

Réglages > Exporter pour R : une ligne par jour (`read.csv`), avec pesée, heure et source, pesée écartée,
tendance et son écart-type, pente (kg/semaine), résidu, dépense, sommeil, minutes de séance, séance du
midi, longue sortie, composition (taux de graisse, masses grasse et maigre, masse maigre Health Connect,
eau, os) et leurs tendances, toutes les réponses, nombre de boissons et unités d'alcool. Depuis la v1.7 :
dépense retenue (`kcal_retenue`, NA les jours sans montre), tendance et écart de l'eau, poids et masse
grasse corrigés de l'eau (`tendance_kg` suit le poids corrigé ; `residu_kg` reste pesée brute − tendance, voir v1.16).
Depuis la v1.9, en fin de ligne : `levier_semaine` (entre guillemets), `budget_bieres`, `bieres_eq33`,
`plan_bieres`, `plan_repas_dehors`, `nb_seances` ; depuis la v1.10 : `pas_total`, `pas_hors_sport` (Garmin) ; depuis la v1.11 : `proteines_g`. Depuis la v1.11,
`levier_semaine` et `budget_bieres` suivent la semaine du dimanche, et `unites_alcool` vaut NA pour « oui » sans
boisson saisie. Depuis la v1.16 : colonne `selle` (réponse), `correction_selle_kg` en fin de ligne (retirée de la
pesée, NA sinon), et `residu_kg` = pesée brute − correction de la selle − tendance.

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
- **v1.9** : objectif de perte, onglet Cap — couloir de pente, levier de la semaine, plan du week-end,
  budget bière ajusté par la pente, signal de la semaine, colonnes de l'objectif dans l'export
- **v1.10** : pas hors sport (lecture des pas, soustraction des séances, base et cible, levier, diagnostic
  des tranches, colonnes `pas_total` et `pas_hors_sport`)
- **v1.11** : revue du 11/11/2026 — couloir d'effort (modèle de Hall), semaine du dimanche, verdicts figés,
  rodage de 2 semaines, budget figé ; marches hors séances, lecture des pas limitée et erreurs signalées, alcool
  « oui » sans boisson = NA ; protéines en portions ; carte de vérification de la VFC ; ménage (bloc « plusieurs
  semaines », croisés sur questions en pause, cafés et énergie en pause, test du double comptage, tendance
  calculée une seule fois) ; simulation du couloir dans les tests du build
- **v1.12** : bilan du dimanche (dimanche 19 h, dans le Cap) : effort et bande de Hall, balance de la
  semaine, graisse et maigre sur 4 semaines, protection du muscle, effort déclaré et face-à-face mensuel,
  propositions de levier et de budget à valider ; mécanismes en probabilités bayésiennes à lieu de dîner égal,
  levier comparé aux leviers non choisis ; portions de protéines réglables
- **v1.13** : apports du Labo avec ρ de Hall au lieu des 7 700 kcal/kg et semaines du dimanche (simulation :
  biais +43 → 0 kcal/j, couverture de l'IC 95 % 88 → 95 %) ; calcul du Cap hors du fil principal, erreur affichée
- **v1.14** : Labo réorganisé (Ce qu'on sait, trois questions, explications repliées) ; une seule échelle de
  preuve (piste / probable / solide) pour le lendemain et les effets croisés, sans Benjamini-Hochberg ; semaine type,
  d'où vient l'écart du jour, graisse et maigre dans le temps, compensation après le sport ; le bilan du dimanche
  annonce aussi les liens du lendemain et les effets croisés
- **v1.15** : la cascade (d'où vient la perte depuis le départ), dans le Labo et dans le bilan à la place du
  face-à-face
- **v1.16** : pesée et selle (question du matin, correction avant le Kalman, effet croisé avec le soir copieux)
- **Après le 26/11** (à évaluer) : horizon eau en modèle espace-état, recalage du bruit et des intervalles de
  l'effort, VFC si Garmin l'envoie
- **v2** : journal alimentaire (scan Open Food Facts, portions CIQUAL) ; refonte du design,
  plus belle et plus pratique
