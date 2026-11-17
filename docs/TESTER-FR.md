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
les modifications d’une seule occurrence, les widgets et la migration Google ne
font pas partie de cette version.

## Publier plus tard

Après validation sur le Pixel et le NAS : nom/icône définitifs, éventuelles
corrections, signature de publication, fiche et déclarations Play Store, puis
validation explicite avant soumission. Aucun envoi sur le Play Store n’est effectué
par cette livraison de test.
