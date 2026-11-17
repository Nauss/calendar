# Premier essai sur le Pixel

## Installer

1. Conserver Etar et la configuration actuelle : cette alpha ne les remplace pas
   automatiquement et ne modifie pas leurs comptes.
2. Installer l’APK de test, ou brancher le Pixel avec le débogage USB pour une
   installation accompagnée. Aucune manipulation du téléphone n’a été nécessaire
   pendant le développement.
3. Ouvrir **Agenda**. Le calendrier **Sur cet appareil** est vide et utilisable sans
   connexion ni compte Google.

L’APK est signé pour les tests. Ce n’est pas encore la signature de publication
Play Store. Avant une future réinstallation avec une autre signature, il faudra
préserver les événements locaux et synchroniser les modifications en attente.

Pour mettre à jour une alpha existante signée avec le même certificat, installer
la nouvelle APK **par-dessus**, sans désinstaller Agenda ni effacer ses données.
La configuration et le cache doivent être conservés. Si Android refuse la mise
à jour, ne pas contourner le problème en supprimant les données : conserver le
message d’erreur et vérifier d’abord le certificat/la version.

## D’abord sans serveur

Créer un rendez-vous local, le modifier, essayer une journée entière, une répétition
hebdomadaire et la recherche. Fermer puis rouvrir l’application : l’événement doit
rester présent. Essayer Planning, Jour, 3 jours, Semaine et Mois dans le menu.

## Ensuite avec le NAS

Utiliser d’abord **un agenda CalDAV de test**, pas l’agenda personnel principal.
Exporter aussi une sauvegarde côté serveur avant de tester des écritures importantes.

1. Dans Synology Calendar, récupérer l’URL depuis **Compte CalDAV**. Selon la version,
   l’option se trouve dans les paramètres du paquet ou dans le menu de l’agenda.
   Copier l’URL réellement fournie, sans inventer le chemin ni y inclure le mot de passe.
2. Dans Agenda : menu → **Paramètres** → **Compte CalDAV**.
3. Renseigner cette URL HTTPS, l’identifiant et le secret approprié à ce compte.
   Ne pas transmettre le mot de passe dans une conversation ou un ticket public.
4. **Découvrir les agendas**, sélectionner l’agenda de test, puis enregistrer.
5. Choisir cet agenda dans **Agenda par défaut** pour les nouveaux rendez-vous à
   synchroniser. **Sur cet appareil** reste exclusivement local.
6. Vérifier un événement dans Synology Calendar, puis dans Agenda. Modifier un
   événement de test d’un côté, synchroniser de l’autre. Répéter la synchronisation :
   elle ne doit pas créer de copie supplémentaire.
7. Couper temporairement le réseau, créer un événement de test, puis rétablir le
   réseau et synchroniser. L’état en attente doit disparaître après confirmation
   du serveur. Ne pas effacer les données entre-temps.

Une erreur de certificat doit être corrigée côté nom d’hôte/certificat/réseau.
L’application ne possède pas de bouton « ignorer le certificat ». L’option HTTP
local est réservée aux IP privées/loopback et transmet tout sans chiffrement ; ce
n’est pas le réglage recommandé pour un agenda personnel.

## Les « doublons » actuels

Cette appli utilise une base privée et ne demande pas d’accès aux agendas système.
Elle ne recopiera donc pas ses événements dans le compte Google du téléphone.
Cela **ne supprime pas** d’éventuelles copies déjà présentes. Voir le même
rendez-vous dans Google Agenda et Etar n’établit pas, à lui seul, qu’il existe deux
copies : les deux applis peuvent afficher la même source Android.

Au retour sur le téléphone, il faudra relever le compte/agendas affichés par chaque
appli avant de masquer un calendrier, désactiver une synchronisation ou supprimer
quoi que ce soit. Aucun nettoyage automatique n’est prévu.

## Points à vérifier sur le vrai téléphone

- Lisibilité, gestes Retour, clavier, heure/fuseau et thème clair/sombre.
- Connexion au NAS sur Wi-Fi puis via le moyen d’accès distant déjà utilisé.
- Rappels après écran éteint, économie de batterie et redémarrage.
- Modification concurrente : pas d’écrasement silencieux ; conservation d’une copie
  locale lors de la résolution proposée.
- Cas de récurrence réellement utilisés dans l’agenda personnel.

Les rappels de cette alpha sont **silencieux et non exacts**. Les événements importés
complexes peuvent être protégés contre l’édition/la suppression ; les lire et les
exporter reste possible lorsque leur format est pris en charge. Les invitations,
les modifications d’une seule occurrence et la migration Google ne
font pas partie de cette version.

## Modifier un rendez-vous importé

Ouvrir le rendez-vous puis **Modifier**. Les rendez-vous individuels usuels sont
modifiables, y compris lorsqu’ils contiennent un fuseau horaire intégré. Les
champs non pris en charge restent verrouillés avec une explication : par exemple
les horaires d’une série complexe, une règle de récurrence importée, des rappels
complexes ou un fuseau dont les règles ne correspondent pas à l’heure choisie.
Les titres, lieux et notes peuvent rester modifiables sans toucher à ces structures.
Les invitations restent protégées ; ne pas forcer leur conversion en événement
simple. Une modification de série concerne la série, pas seulement l’occurrence.
Les rares fuseaux intégrés dont le décalage UTC comporte des secondes non nulles
ne sont pas pris en charge : ils sont refusés plutôt que réécrits avec un arrondi.

Si un widget ou une notification ouvre une autre destination pendant une
modification, un brouillon modifié exige une confirmation d’abandon. **Annuler**
conserve la saisie. La rotation conserve également le brouillon et cette demande.

Essayer d’abord un événement de test, modifier l’horaire, attendre la fin de la
synchronisation puis vérifier dans le serveur. Une modification concurrente doit
afficher un conflit, jamais écraser silencieusement l’autre version.

## Ajouter les widgets sur le téléphone

1. Dans Agenda : menu → **Paramètres** → **Widgets**.
2. Choisir **Ajouter le widget Planning** ou **Ajouter le widget Mois**.
3. Confirmer dans le lanceur Android et choisir son emplacement.
4. Autre accès : appui long sur un emplacement vide de l’écran d’accueil →
   **Widgets** → **Agenda**. Un appui long sur le widget permet de le redimensionner.

**Planning** affiche les 31 prochains jours du cache ; toucher un rendez-vous
ouvre la bonne occurrence. **Mois** affiche les dates et des repères de présence
d’événements ; toucher une date ouvre la vue Jour. Les flèches et Aujourd’hui
agissent sur ce widget uniquement. Le bouton **+** ouvre la création dans Agenda.

Les widgets suivent les calendriers visibles et le thème choisi dans l’app.
Ils exposent les titres/horaires/lieux sur l’écran d’accueil : en tenir compte
pour les événements confidentiels. Ils n’ajoutent aucun compte ni permission
d’accès aux agendas système. Le rafraîchissement périodique n’est pas exact ;
l’économie de batterie peut le retarder. L’action Actualiser relit le cache ;
la synchronisation réseau demeure celle d’Agenda.

Un message **Aperçu limité** ou une erreur signifie que le widget ne présente pas
tout le calendrier : ouvrir l’app. Le Planning est limité à 64 lignes ; la lecture
de cache des widgets et l’expansion des récurrences ont aussi des limites de sûreté.
Les images du sélecteur de widgets sont des aperçus illustratifs, jamais des
rendez-vous ajoutés dans votre agenda.

## Publier plus tard

Après validation sur le Pixel et le NAS : nom/icône définitifs, éventuelles
corrections, signature de publication, fiche et déclarations Play Store, puis
validation explicite avant soumission. Aucun envoi sur le Play Store n’est effectué
par cette livraison de test.
