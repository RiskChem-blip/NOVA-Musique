# Nova Musique : ta musique sur Android, avec Last.fm et ListenBrainz

Même famille que Nova STREAM : intro « Supernova », vitrine, envies, Top 10, thème Bordeaux.

Nova Musique lit les fichiers musicaux de ton téléphone (MP3, FLAC, M4A, OGG…) et envoie
chaque écoute à tes comptes Last.fm et ListenBrainz.
GitHub fabrique l'APK automatiquement à chaque dépôt du projet, comme pour Nova.

## 1. Mettre le projet sur GitHub (une seule fois)

1. Sur https://github.com, clique sur **+** puis **New repository**.
2. Nom : `nova-musique`. Choisis **Public** (le projet ne contient aucun identifiant), puis **Create repository**.
3. Clique sur le lien **uploading an existing file**.
4. Ouvre le dossier `nova-musique` sur ton ordinateur, sélectionne **tout son contenu**
   (y compris le dossier `.github`) et fais-le glisser dans la page GitHub.
5. En bas, clique sur **Commit changes**.

> Si le dossier `.github` n'a pas été envoyé : **Add file › Create new file**, tape le nom
> `.github/workflows/build.yml`, colle le contenu du fichier `build.yml`, puis **Commit changes**.

## 2. Récupérer et installer l'APK

1. Onglet **Actions** : la fabrication démarre toute seule (environ 5 minutes).
2. Quand la ligne passe au vert ✅, ouvre l'onglet **Releases** : le fichier **nova-musique.apk** est là.
3. Sur le téléphone, ouvre le lien direct, télécharge `nova-musique.apk`, ouvre-le et accepte l'installation.

Lien direct, toujours vers la dernière version :
`https://github.com/TON-PSEUDO/nova-musique/releases/latest/download/nova-musique.apk`

## 3. Écouter avec tes comptes (mode YouTube)

L'accueil affiche tes écoutes récentes, tes titres du mois, tes titres aimés et les
playlists que ListenBrainz prépare pour toi. Un titre touché est cherché sur YouTube et
joué par le lecteur YouTube officiel, intégré à l'appli.

- **Versions « YouTube Music »** : l'appli choisit en priorité l'audio officiel publié par
  les chaînes « Artiste - Topic », le même son que YouTube Music.
- **Règles YouTube** : la vidéo reste visible, à la place de la pochette dans le lecteur.
  Quand tu navigues, au choix (**Comptes › Vidéo YouTube**) : une fenêtre flottante de
  200 × 200 px que tu ranges dans le coin de ton choix (glisse-la par sa poignée), ou la
  pause dès que tu quittes le lecteur. L'écran reste allumé pendant la lecture et la lecture
  se met en pause si tu quittes l'appli. Pas de téléchargement hors ligne : pour le hors
  ligne et l'écoute écran éteint, utilise les fichiers du téléphone.
- **Spotify et Deezer** : relie-les à Last.fm (et Spotify à ListenBrainz) depuis leurs
  réglages ; tout ce que tu y écoutes apparaîtra dans l'accueil.

### La clé YouTube intégrée à l'appli (secret GitHub)

Pour ne plus jamais la retaper : range-la une fois dans les secrets du dépôt, et chaque APK
fabriqué par GitHub l'aura déjà.

1. Sur GitHub, dans le dépôt : **Settings › Secrets and variables › Actions › New repository secret**.
2. Nom : `YOUTUBE_API_KEY`. Valeur : ta clé (elle commence par « AIza »). **Add secret**.
3. **Actions › Fabriquer l'APK Nova Musique › Run workflow** (ou attends la prochaine mise à jour).

La clé n'apparaît jamais dans le code ni sur GitHub. Elle est en revanche **dans l'APK** :
garde le dépôt privé, ne partage pas l'APK, et limite la clé à « YouTube Data API v3 »
dans Google Cloud (étape 4 ci-dessous). Dans **Comptes › YouTube**, tu peux toujours coller
une autre clé à la main ; elle remplace alors la clé intégrée.

### Créer la clé YouTube (gratuite, une seule fois)

1. Ouvre https://console.cloud.google.com/apis/library/youtube.googleapis.com avec ton compte Google.
2. Crée un projet « Nova Musique » si on te le demande, puis **Activer**.
3. **Identifiants › Créer des identifiants › Clé API**.
4. Conseillé : **Modifier la clé › Restrictions d'API › YouTube Data API v3**.
5. Dans Nova Musique : **Comptes › YouTube**, colle la clé (elle commence par « AIza »).

La clé permet environ **100 recherches de nouveaux titres par jour**. Chaque titre trouvé
est retenu : le réécouter ne coûte plus rien. Le compteur repart à zéro chaque nuit
(minuit, heure de Californie).

## 4. Mettre ta musique sur le téléphone (facultatif)

Pour écouter aussi sans internet : copie tes fichiers dans le dossier **Music** du téléphone
(câble USB, carte SD ou transfert depuis l'ordinateur), puis **Bibliothèque › Autoriser l'accès**.
Après un ajout : **Comptes › Rechercher de nouveaux morceaux**.

Les informations (titre, artiste, album, pochette) viennent des tags des fichiers.
Si un album s'affiche mal, corrige ses tags sur l'ordinateur (MusicBrainz Picard, Mp3tag…).

## 5. Connecter Last.fm

1. Crée une clé API gratuite : https://www.last.fm/api/account/create
   - Nom de l'application : `Nova Musique`. Les autres champs peuvent rester vides.
2. Last.fm affiche une **API key** et un **Shared secret** (32 caractères chacun).
3. Dans Nova Musique : **Comptes › Last.fm**, colle la clé et le secret, puis ton nom
   d'utilisateur et ton mot de passe Last.fm. Le mot de passe sert une seule fois
   à ouvrir la session et n'est pas enregistré.

## 6. Connecter ListenBrainz

1. Sur https://listenbrainz.org/settings/, copie ton **User token**.
2. Dans Nova Musique : **Comptes › ListenBrainz**, colle le jeton.

## Comment les écoutes sont comptées

Un titre est envoyé quand il dure plus de 30 secondes et que tu l'as écouté
**à moitié ou 4 minutes** (la règle de Last.fm et ListenBrainz).
« En cours d'écoute » s'affiche sur tes profils dès le début du titre.
Sans réseau, les écoutes sont gardées sur le téléphone et partent à la prochaine connexion.

## Mettre à jour l'appli

Remplace les fichiers modifiés sur GitHub (**Add file › Upload files**) : un nouvel APK
se fabrique tout seul. Toutes les versions sont signées avec la même clé
(`app/novamusique.keystore`), donc une mise à jour s'installe par-dessus sans rien perdre.
Ne supprime pas ce fichier.
