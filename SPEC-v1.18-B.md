# SPEC — Suivi_nutri · lot v1.18 B « Phrase du repas »

> Fiche validée le 26/11/2026. Référence : `claude/suivi-nutri.md` (3.1 B, 4.8, 6, 7.2). Socle commun appliqué (planchers, conventions).
> Repo : `dash52000/Suivi_nutri`. Copie de la fiche du projet « remise en forme », à mettre à jour dans le même commit que tout changement.

## 1. Résumé
Quatre champs libres (Matin, Midi, Soir, Grignotage), lus par un LLM qui ne produit que du JSON ; les kcal viennent de CIQUAL dans le code ; la balance apprend un biais par contexte. Sortie principale : l'apport journalier avec barre d'erreur. **Le total vient de la balance, la répartition vient des phrases.**

## 2. Public et moment d'usage
Damien seul. Le soir, dans le questionnaire (ou son rattrapage du lendemain à 8 h 30), en dictée. Aucune notification en plus.

## 3. Ce que ça remplace, et pourquoi
- Remplace la 3.1 B du 20/11 : tirage du soir seul, phrases spontanées exclues, Groq seul.
- Les journaux alimentaires classiques lâchent parce qu'ils exigent précision et saisie au moment du repas. Ici, la précision se déduit de la formulation et s'accumule dans le modèle ; rien n'est obligatoire.

## 4. Écrans et parcours
- **Ce soir** : quatre champs, exemple grisé selon le contexte, micro (dictée Android). Les moments tirés sont marqués « demandés ».
  - Matin tiré : « Comme d'hab ? » ou une phrase.
  - Midi, Soir tirés : une phrase ou « Pas maintenant ».
  - Grignotage tiré : une phrase, « Rien » ou « Pas maintenant ».
  - Midi pré-rempli « Restes de [plat de la veille], assiette normale » si tupp et tupp remplis la veille.
  - Quantité manquante : petite · normale · grosse (un tap, sans relance).
  - Jamais de kcal sur cet écran.
- **Bilan du dimanche et Labo** : apport journalier avec barre (moyenne 4 semaines, jour isolé), répartition matin / midi / soir / grignotage, écarts entre contextes (piste / probable / solide). Phrases en clair par gabarits fixes, détails repliés.
- **Réglages** : clés Gemini et Groq (Android Keystore) ; chaîne de modèles ordonnée et modifiable ; bouton « Tester les modèles » (un appel par maillon, affiche ceux qui répondent).

## 5. Valeurs de départ (décidées le 26/11)
| Sujet | Défaut |
|---|---|
| π de tirage | soir chargé 1 · soir normal 0,5 · midi 0,6 · matin 0,15 (0,5 tant qu'il y a moins de 3 phrases du matin) · grignotage 0,4 · plancher 0,1 · mêmes π semaine et week-end |
| Soir chargé | **resservi (« un peu » ou « oui ») ou alcool**, d'après les taps déjà cochés. Le dessert et le dîner chez les beaux-parents n'en font pas un : ce sont ses soirs ordinaires (décision du 26/11, voir 14) |
| Budget | environ 10 **phrases** par semaine ; les taps « Rien » et « Comme d'hab » ne comptent pas dans le budget |
| Recalcul des π | Neyman 4 semaines après le premier tirage, puis toutes les 4 semaines ; **un dimanche seulement**, jamais en cours de semaine ; π réellement utilisé enregistré avec chaque tirage |
| Marge des spontanées | σ × 1,5 au départ, décroissance linéaire jusqu'à × 1 à 25 phrases comparables (même contexte) si aucun écart spontanée / tirée n'est détecté ; avec écart : reste × 1,5 et tirées vers le biais des phrases tirées |
| Petit-déj habituel | 8 semaines glissantes, au moins 3 phrases ; appris des seules phrases tirées (poids 1/π) ; « Comme d'hab » confirme sans apprendre ; « Comme d'hab ? » absent avant 3 phrases ; nouvelle habitude si les 3 dernières phrases tirées s'écartent toutes au-delà de leur marge **et dans le même sens** (l'appli repart de ces 3 et le signale dans le Labo) |
| Chaîne LLM | `gemini-3.8-flash` → `gemini-4-hydrogen` → `gemini-3.6-flash` → `gemini-3.5-flash-lite` → `gemini-3.1-flash-lite` → `gemini-2.5-flash` → `gemini-2.5-flash-lite` → Groq `openai/gpt-oss-120b` |
| Offre | **gratuite uniquement** (pas d'offre payante) |
| Réglages LLM | température 0 ; niveau de réflexion bas (Hydrogen refuserait le niveau minimal : à vérifier au code) ; budget de temps de la chaîne 60 s, en arrière-plan |

Notes sur la chaîne (vérifié le 26/11) : 3.8 Flash, 3.6 Flash, 3.5 Flash-Lite, 3.1 Flash-Lite sont stables dans la doc Google ; 3.1 Flash-Lite a un arrêt prévu au plus tôt le 7/05/2027 ; les 2.5 restent servis aux seuls anciens utilisateurs (404 possible) ; `gemini-4-hydrogen` (GA du 12/11 d'après plusieurs sources) n'est pas encore sur la page officielle des modèles. Un nom retiré peut être redirigé vers un autre modèle (3.7 Flash → 3.8) : d'où l'enregistrement du modèle qui a réellement répondu.

## 6. Modèle de données
- `PhraseRepas` : id stable, jour, moment (matin / midi / soir / grignotage), texte, `origine` (tirée / chargée / spontanée), tardive (oui / non), horodatage.
- `TirageMoment` : jour, moment, strate (moment × type de jour × taps), π, graine, réponse (phrase / rien / comme_dhab / refus / ignoré ; vide = moment encore ouvert), tardive (oui / non). Type de jour : week-end = vendredi soir, samedi, dimanche ; le reste = semaine.
- `Extraction` : id de phrase, JSON brut, modèle ayant répondu, version du prompt, version de CIQUAL, erreurs et bascules.
- `AlimentExtrait` : nom générique, code CIQUAL (ou NA), grammes, `portion_source`, moment, rab, σ appliquée. `BoissonExtraite` : nom, format, degré.
- `BiaisContexte` : niveau (global / midi-soir / contexte), contexte, β a posteriori (moyenne, écart-type), nombre de repas, date du calcul.
- `ApportJour` : kcal par moment, kcal du jour, bornes à 95 %, sources de chaque moment (décrit / contexte / tap).
- `HabitudeMatin` : kcal, σ, fenêtre, phrases utilisées.
- Stockage : `repas.json` (hors Room), écrit en entier de façon atomique, avec un numéro de version (absent = 1 ; une version plus récente est refusée sans rien écrire). Il contient les phrases, les tirages, les extractions et un bloc `reglages` (graine fixe, table des π en vigueur, jour du dernier recalcul). Aliments et boissons ne sont jamais stockés : ils se relisent depuis le JSON brut et CIQUAL.
- Les clés API restent dans le Keystore : hors base, hors sauvegarde JSON, hors export.

## 7. Notifications et IA
- **Notifications** : aucune nouvelle. Le rattrapage de 8 h 30 existant couvre les moments tirés non remplis (réponse marquée tardive).
- **IA** : JSON seulement, jamais de texte affiché, aucun calcul de kcal ou de nutriments (garde-fou 6.4).
- **Schéma unique** pour tous les maillons, au format strict de Groq : toutes les propriétés dans `required`, `additionalProperties: false`, optionnel = union avec `null`. Sortie : aliments (nom, grammes, `portion_source`, moment, rab), boissons (nom, format, degré), tags (lieu, rab, dessert, alcool : vrai / faux / null), `confiance`.
- **Erreurs de la chaîne** :
  - 503, 429, délai dépassé : un nouvel essai sur le même modèle après une courte attente, puis maillon suivant ;
  - JSON invalide : un nouvel essai, puis maillon suivant ;
  - 401, 403 : fournisseur sauté et noté (clé à revoir) ;
  - **404 : modèle retiré, sauté et noté** (ajout du 26/11) ;
  - 400 : pas de bascule, notée ;
  - chaîne entière en échec ou hors ligne : phrase gardée (données NA), relance à l'ouverture suivante.
- **Envoyé aux services** : le texte de la phrase et son moment, rien d'autre (ni poids, ni Garmin, ni date).
- **Conditions de l'offre gratuite Gemini** (conditions du 23/03/2026) : Google utilise les contenus et réponses pour améliorer ses produits ; des relecteurs humains peuvent les lire ; les conditions déconseillent d'y envoyer des informations personnelles ; elles réservent aussi l'offre payante aux applis proposées à des utilisateurs de l'EEE et l'API à un usage professionnel. Appli pour Damien seul : zone grise, **risque accepté** (offre gratuite maintenue). Conditions de Groq : à vérifier avant le code.

## 8. Dérogations aux planchers
- IA dans l'appli : demandée par cette fiche (exception au défaut « pas d'IA »).
- Configuration avant usage : les clés se saisissent une fois ; sans clé, les champs n'apparaissent pas et rien ne change.
- Aucune autre : tout est facultatif ; refus gratuit, sans relance ni série (zéro culpabilité) ; action principale en 1 à 3 taps ; export R.

## 9. « Fini quand… »
1. **Saisie** : sans clé, l'export est identique au bit près à la v1.17 ; avec clé, les quatre champs et la dictée marchent, exemple grisé selon le contexte.
2. **Tirage** : reproductible à graine fixe ; π toujours > 0 et enregistré ; soir chargé (resservi ou alcool) à π = 1 ; environ 10 phrases par semaine en simulation (≈ 11,5 avec les π de départ et 53 % de soirs chargés, ramenées à 10 par le premier recalcul).
3. **Moment tiré** : « Rien » = zéro observé ; « Comme d'hab » = habituel ; refus sans relance, noté au journal ; grignotage vide hors tirage = NA.
4. **Extraction** : chaque cas d'erreur de la chaîne se comporte comme en section 7, testé sur des réponses figées par modèle ; le modèle ayant réellement répondu est enregistré.
5. **Marges** : explicite ±15 %, standard ±40 %, grosse / petite ±30 %, confiance basse × 1,5, spontanées × 1,5 décroissant (tests numériques).
6. **Biais** : les simulations de la section 10 passent leurs critères chiffrés.
7. **Apport journalier** : moyenne 4 semaines et jour isolé (barre plus large) ; couverture à 95 % entre 92 et 98 % (critère provisoire, à confirmer à la première exécution).
8. **Affichage** : kcal absentes de Ce soir, présentes au bilan et au Labo.
9. **Export et journal** : colonnes de la 3.1 B présentes ; journal : moment demandé, ouvert, rempli, dicté, refusé, ignoré, tardif ; pré-cochage validé / corrigé ; modèle ayant répondu, bascules, erreurs par fournisseur.
10. **Clés** : absentes du repo, des secrets GitHub, de la sauvegarde JSON et de l'export.

## 10. Simulations avant de coder (`SimulationApprentissageTest`, vrai code)
60 tirages à 6, 12 et 24 semaines ; bruit actuel (0,42 kg ; 0,05 kg/j) ; à relancer avec le bruit réel après le recalage en R. Chaque simulation a un critère chiffré inscrit dans le test. On ne passe au bloc suivant que si le précédent tient.
1. **Le modèle tient debout** : 1 (sans biais : centré sur zéro), 2 (biais global −15 % : retrouvé, délai mesuré), 7 (couverture à 95 %).
2. **Ce que la balance sépare** : 3 (biais seulement chez les beaux-parents, pas attribué au global), 5 (grignotage caché +200 kcal/j dans le « non expliqué »).
3. **Biais de sélection** : 4 (refus des soirs chargés), 6 (spontanées sur repas copieux, détectées par `origine_phrase`), 9 (« Rien » oublié, grignotage sous-déclaré de 30 %).
4. **Plan de tirage** : 8 (Neyman contre uniforme à budget égal) ; fixe les π définitifs.
5. **Ajouts du 26/11** : 10 (simulation 7 avec marge des spontanées × 1, × 1,5, × 2) ; 11 (changement d'habitude du petit-déj rattrapé par la fenêtre de 8 semaines et la règle des 3 phrases).

## 11. Découpage
- **Tranche 1** : saisie, tirage, extraction, biais par contexte, apport journalier.
- **Tranche 2** : protéines des phrases, sel et glucides du soir (expositions du lendemain), part « portions des repas » de la cascade ; chacune simulée avant d'être affichée (garde-fou 1).

## 12. Hors périmètre
Plats types et fiches par plat ; scan Open Food Facts ; LLM qui rédige le bilan, interroge le Labo ou alimente le coach ; prescription ; kcal dans Ce soir ; notification ou relance en plus ; offre payante ; Cap lisible (3.1 A, lot à part) ; recalage en R du 26/11 (section 5 du doc).

## 13. Idées reportées
- Hydrogen en tête de chaîne si « Tester les modèles » et les extractions le justifient.
- Vérifier les conditions de Groq (réutilisation des contenus) avant le code.

## 14. Journal des décisions
Les décisions de conception du 26/11 sont en 7.2 de `suivi-nutri.md`. Ajouts de cette fiche :
| Date | Décision | Pourquoi |
|---|---|---|
| 26/11 | π de départ : soir chargé 1, soir normal 0,5, midi 0,6, matin 0,15, grignotage 0,4 ; plancher 0,1 ; taps hors budget | Budget d'environ 10 phrases ; un tap ne coûte presque rien |
| 26/11 | Marge des spontanées σ × 1,5, décroissante jusqu'à × 1 à 25 phrases comparables | Même facteur que la confiance basse ; pas de seuil brutal |
| 26/11 | Petit-déj habituel : 8 semaines, phrases tirées seulement, nouvelle habitude sur 3 phrases | Une phrase spontanée du matin est un écart par construction |
| 26/11 | Offre gratuite uniquement ; chaîne de 7 modèles Gemini puis Groq ; 404 = modèle sauté ; bouton « Tester les modèles » ; envoi limité au texte et au moment | Choix de Damien ; Google réutilise les contenus de l'offre gratuite |
| 26/11 | Simulations en quatre blocs, plus 10 et 11 | Invalider le design au plus tôt ; vérifier les choix du jour |
| 26/11 | Deux tranches | Le cœur d'abord ; les sorties dérivées sont simulées avant affichage |
| 26/11 | Étape 1 codée : `moment` d'un aliment en `anyOf` (chaîne ou null) ; réflexion `thinkingLevel` LOW pour Gemini 3+, `thinkingBudget` 512 pour les 2.x ; schéma Gemini dans `responseFormat` ; 5xx traités comme 503 ; un 400 Groq `json_validate_failed` traité comme JSON invalide ; un 400 Gemini `API_KEY_INVALID` traité comme une clé refusée ; hors ligne = arrêt immédiat | Doc Gemini et Groq vérifiée au code : `null` dans un `enum` non garanti chez Gemini, `thinkingLevel` refusé par les 2.x |
| 26/11 | Soir chargé (π = 1) = resservi ou alcool seulement ; le dessert et le dîner chez les beaux-parents n'en font plus un | Export du 26/11 (30 soirs) : beaux-parents 25 soirs, dessert 15, resservi 10, alcool 7. Avec la règle de la fiche, 100 % des soirs étaient chargés (13,1 phrases par semaine, jamais de tirage au soir) ; avec la nouvelle, 16 soirs sur 30 (53 %), ≈ 11,5 phrases par semaine au départ |
| 26/11 | Étape 2 codée (`repas/` : Modeles, Marges, HabitudeMatin, Tirage, StockRepas ; 74 tests verts avec kotlinc, 5 règles cassées exprès pour vérifier que les tests les voient) : réponse sans valeur « tardif », un drapeau `tardive` à part (un « Rien » rattrapé reste un zéro observé) ; week-end = vendredi soir, samedi, dimanche ; tirage par suite de Weyl (u fixé par graine, jour et moment ; équilibré sur le jour de semaine) ; Neyman par dichotomie, π entre le plancher et 1, soirs chargés figés à 1 ; coût du grignotage 1,5 et part de phrases 0,5 (matin, grignotage) en hypothèses de départ ; recalcul un dimanche, 4 semaines après le premier tirage puis toutes les 4 semaines ; nouvelle habitude du matin : trois écarts dans le même sens ; `repas.json` version 1 avec bloc `reglages` | Garde-fous 1 et 6 : tout est testé avec le vrai code. Le coût et la part de phrases sont remplacés par les valeurs observées dès que le journal d'usage les donne ; « même sens » sera vérifié par la simulation 11 |

## 15. Checklist « terminé » du lot
- [ ] Simulations 1 à 11 passent leurs critères
- [ ] Chaîne testée avec « Tester les modèles »
- [ ] Conditions de Groq vérifiées
- [ ] Export R et journal d'usage vérifiés
- [ ] Revue avant publication (garde-fou 3)
- [ ] SPEC et `suivi-nutri.md` mis à jour dans le même commit
