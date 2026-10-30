# Nova Musique (Android, WebView + assets/index.html)
Problème : faire disparaître l'écran YouTube obligatoire pendant la lecture.
Choix : complètement invisible.
## Fait (juin 2026)
- Lecteur YouTube caché (cadre 2px, opacité ~0, sans clics), toujours actif pour garder le son
- Pochette affichée dans le lecteur plein écran à la place de la vidéo
- LISEZMOI mis à jour
- Lecture YouTube en arrière-plan : WebView toujours "visible", plus de pause sur hidden/visibilitychange,
  service YtService (premier plan, wake lock + wifi lock, notification précédent/pause/suivant -> window.novaYt)
## Backlog
- Laisser l'écran s'éteindre pendant la lecture YouTube (keepScreenOn)
- Notification style média / écran verrouillé avec pochette
