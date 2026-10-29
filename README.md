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

## Feuille de route

- **v0.1** : squelette, chaîne de build et test de mise à jour sans perte de données
- **v1** : Health Connect (poids, dépense, activités, sommeil), questionnaire du soir,
  notif, alcool en unités, estimation des apports, labo, export/import
- **v2** : journal alimentaire (scan Open Food Facts, portions CIQUAL)
