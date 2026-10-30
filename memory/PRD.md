# Nova Musique (Android, WebView + assets/index.html)
Problème : faire disparaître l'écran YouTube obligatoire pendant la lecture.
Choix : complètement invisible.
## Fait (juin 2026)
- Lecteur YouTube caché (cadre 2px, opacité ~0, sans clics), toujours actif pour garder le son
- Pochette affichée dans le lecteur plein écran à la place de la vidéo
- LISEZMOI mis à jour
## Backlog
- Lecture en arrière-plan (actuellement pause à la sortie de l'appli, MainActivity.onStop)
