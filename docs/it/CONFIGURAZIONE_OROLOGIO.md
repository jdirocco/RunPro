# Configurare il Galaxy Watch 5 Pro per sviluppare e provare RunPro

Guida per il proprietario, in italiano. La versione per gli agenti (in inglese) è `docs/WATCH_SETUP.md`
(issue #6). I nomi dei menu possono cambiare leggermente con la versione di One UI Watch: se una voce
non c'è esattamente con questo nome, cerca quella più simile nello stesso menu.

## 0. Cosa serve

- Il Mac con l'Android SDK in `~/Library/Android/sdk` (c'è già). `adb` è in
  `~/Library/Android/sdk/platform-tools/adb`. Per averlo nel terminale:

  ```bash
  export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
  ```

  (oppure aggiungi la riga a `~/.zshrc`).
- Orologio e Mac sulla **stessa rete Wi-Fi**. Le reti "ospiti" o aziendali con isolamento dei client non
  funzionano: in quel caso usa l'hotspot del telefono o la rete di casa.
- Orologio carico almeno al 50 %: il Wi-Fi consuma batteria.

## 1. Attivare le Opzioni sviluppatore

1. Sull'orologio: **Impostazioni → Info sull'orologio → Informazioni software**.
2. Tocca **Versione software** 5–7 volte, finché compare "Modalità sviluppatore attivata".
3. Torna in **Impostazioni**: in fondo c'è ora **Opzioni sviluppatore**.

## 2. Attivare il debug

In **Impostazioni → Opzioni sviluppatore**:

1. Attiva **Debug ADB**.
2. Attiva **Debug wireless** (in alcune versioni "Debug tramite Wi-Fi") e conferma.
3. Consigliato durante lo sviluppo: **Rimani attivo** (lo schermo non si spegne mentre è in carica).

## 3. Tenere acceso il Wi-Fi dell'orologio

Quando è collegato al telefono via Bluetooth, il Galaxy Watch spegne il Wi-Fi per risparmiare batteria.

1. **Impostazioni → Connessioni → Wi-Fi**: attivalo e collegati alla stessa rete del Mac.
2. Se c'è l'opzione, scegli **Sempre attivo** invece di "Automatico".
3. Se il Wi-Fi si spegne lo stesso, disattiva per un momento il Bluetooth del telefono durante
   l'abbinamento.

## 4. Abbinare il Mac all'orologio (una volta sola)

1. Sull'orologio: **Opzioni sviluppatore → Debug wireless → Associa nuovo dispositivo**.
   Compaiono un **codice di 6 cifre** e un indirizzo **IP:porta di associazione** (es. `192.168.1.42:37145`).
2. Sul Mac:

   ```bash
   adb pair 192.168.1.42:37145
   ```

   e inserisci il codice di 6 cifre. Deve rispondere `Successfully paired`.

In alternativa, da Android Studio: **Device Manager → Pair Devices Using Wi-Fi → Pair using pairing code**.

## 5. Collegarsi (ogni sessione di lavoro)

1. Sull'orologio, nella schermata **Debug wireless** (non quella di associazione), leggi
   **Indirizzo IP e porta** (es. `192.168.1.42:41567`). Questa porta è **diversa** da quella di
   associazione e cambia ogni volta che il debug wireless si riattiva.
2. Sul Mac:

   ```bash
   adb connect 192.168.1.42:41567
   ```

   ```bash
   adb devices
   ```

   L'orologio deve comparire come `device`. Se compare `unauthorized`, accetta la richiesta sull'orologio
   (spunta "Consenti sempre da questo computer").

## 6. Installare RunPro

Dalla cartella del progetto `/Users/juridirocco/development/RunPro`:

```bash
./rp install
```

Compila `:wear` e lo installa sull'orologio collegato. Controlla prima che il dispositivo sia autorizzato e che sia
davvero un orologio Wear OS.

Se hai anche un telefono collegato con adb, indica l'orologio (come argomento o con `ANDROID_SERIAL`):

```bash
./rp install 192.168.1.42:41567
```

RunPro compare nell'elenco app dell'orologio.

### App RunPro sul telefono (sincronizzazione delle corse)

Le corse finite passano dall'orologio al telefono tramite il Data Layer di Wear OS. Funziona solo se le due app
hanno lo stesso id (`com.runpro.wear`, anche quella del telefono) e sono firmate con la stessa chiave: installa
entrambe dallo stesso Mac (build debug) oppure entrambe da Play, mai una per tipo.

Con il telefono collegato via adb (solo lui, oppure indicato con `ANDROID_SERIAL`):

```bash
ANDROID_SERIAL=<seriale del telefono> ./gradlew :mobile:installDebug
```

Durante la corsa RunPro scrive un giornale in `files/journals/<id corsa>.journal`; alla fine lo sincronizza
su disco e lo chiude prima di inviarlo al telefono. In caso di errore di scrittura il file rimane per il recupero;
la corsa non viene considerata salvata correttamente.

Sull'orologio una corsa resta salvata finché il telefono non conferma di averla ricevuta; se il telefono è lontano
la manda appena torna raggiungibile o quando riapri RunPro. La prova completa è la F8 in `docs/FIELD_TESTING.md`.

## 7. Impostazioni dell'orologio per correre con RunPro

Da fare una volta dopo la prima installazione, e da ricontrollare dopo ogni aggiornamento di One UI Watch.

**Permessi di RunPro.** Al primo avvio RunPro elenca i permessi che mancano: tocca **Consenti** e accettali.
Puoi anche cambiarli da **Impostazioni → App → RunPro → Autorizzazioni**:

| Permesso | Valore | Se manca |
|---|---|---|
| Posizione | Consenti durante l'uso dell'app (precisa) | Niente GPS: niente distanza e passo |
| Posizione, seconda richiesta | **Consenti sempre** | Se l'orologio chiude e riapre RunPro durante una corsa, il GPS non riparte |
| Sensori del corpo / Frequenza cardiaca | Consenti | Niente battito dal polso |
| Attività fisica | Consenti | Niente cadenza |
| Dispositivi nelle vicinanze (Bluetooth) | Consenti | Niente fascia cardio né footpod |
| Notifiche | Consenti | La corsa continua, ma senza notifica né icona sul quadrante |

La posizione "sempre" viene chiesta dopo le altre, con una schermata a parte. Se scegli **Non ora**,
RunPro lo richiede al prossimo avvio. Basta uno tra posizione, battito e attività fisica per avviare una corsa.

**Batteria: RunPro non deve mai essere sospesa.** È il problema più comune delle app di corsa sui Galaxy Watch.
RunPro controlla l'impostazione 1 e mostra il comando da eseguire finché manca. L'impostazione 2 non si
può leggere dall'app: RunPro te la ricorda finché non tocchi **Fatto, controllato**.

1. **Esclusione dall'ottimizzazione della batteria.** Sul Galaxy Watch (Wear OS 6) non c'è nessuna
   impostazione per farlo: la scheda **Impostazioni → App → RunPro** non ha la voce Batteria. Si fa una
   volta da adb, con l'orologio collegato (punto 5):

   ```bash
   adb shell dumpsys deviceidle whitelist +com.runpro.wear
   ```

   Controllo: `adb shell dumpsys deviceidle whitelist | grep runpro` deve stampare una riga. L'esclusione
   resta dopo `./rp install`; si perde solo disinstallando RunPro. Riapri RunPro: l'avviso sparisce.
2. Sul telefono, app **Galaxy Wearable → Impostazioni orologio → Batteria → Limiti utilizzo in background**
   (o "App in sospensione"): RunPro **non** deve essere nell'elenco delle app in sospensione o
   in sospensione profonda. Se sul telefono è installata anche l'app RunPro, controlla pure
   **Impostazioni → Assistenza dispositivo → Batteria → Limiti utilizzo in background** del telefono: Android
   può metterla da solo in **Applicazioni in sospensione** (successo nella prova di #10). Toglila
   (**⋮ → Rimuovi**) e aggiungila ad **Applicazioni mai in sospensione**.

**Come si registra una corsa.** Tocca **Preparati**: RunPro cerca il GPS e il battito e mostra se sono
pronti. Aspetta **GPS: pronto** e tocca **Avvia** (puoi avviare anche prima: la distanza parte quando arriva il
GPS) oppure **Indietro** per rinunciare. Durante la corsa vedi tempo, distanza, passo e battito, con **Giro**,
**Pausa**/**Riprendi** e **Stop**. Stop chiede conferma: **Continua a correre** è la prima scelta, la corsa
si ferma solo con **Ferma e salva**. Alla fine il riepilogo mostra i totali e **Salvata**; se mostra
**Non salvata**, apri una issue **Field report**.

**Durante una corsa** RunPro mostra un'icona sul quadrante (Ongoing Activity) con il tempo che scorre:
toccala per tornare all'app. La notifica fissa esiste ma il Galaxy Watch non la mostra nel pannello
notifiche (scorrendo da sinistra): la mostra come quell'icona. Se l'icona sparisce a corsa in corso,
l'orologio ha chiuso RunPro: segnalalo con una issue **Field report**.

**Posizione**: **Impostazioni → Posizione** attiva. RunPro usa solo il GPS dell'orologio.

**Fascia cardio e altri sensori Bluetooth.** Un sensore BLE accetta di solito **una sola connessione**:

1. La fascia **non** deve essere collegata al telefono, a Samsung Health o a Gear Tracker mentre corri
   con RunPro. In Samsung Health (orologio e telefono) scollega la fascia dagli **Accessori**.
2. Non abbinarla da **Impostazioni → Connessioni → Bluetooth** dell'orologio: la aggiungi da RunPro
   (Sensori → Aggiungi), che gestisce la connessione da sé. Se l'avevi abbinata lì, scegli **Dimentica**.
3. Chiudi Gear Tracker prima di correre: solo un'app alla volta può usare l'allenamento di Health Services.
   Se un'altra app ne sta registrando uno, RunPro lo segnala mentre si prepara; quando avvii la corsa,
   RunPro prende Health Services e l'allenamento dell'altra app si chiude. Se invece un'altra app avvia un
   allenamento durante la corsa, RunPro continua a registrare (tempo, fascia) ma perde GPS e cardio del
   polso fino alla corsa successiva.
4. In Samsung Health disattiva **Rileva automaticamente gli allenamenti** per la corsa, così non parte un
   secondo allenamento in parallelo.

## 8. Prima di una prova sul campo

Segui `docs/FIELD_TESTING.md`. In breve:

1. Installa la build da provare (punto 6).
2. Controlla permessi, batteria e fascia (punto 7).
3. In RunPro: **Impostazioni → Registra traccia di debug** attivo.
4. Dopo la corsa: **Diagnostica → Esporta log**, poi apri una issue **Field report** su GitHub con i file.

## 9. Problemi frequenti

| Sintomo | Soluzione |
|---|---|
| `adb connect` risponde `failed to connect` | Wi-Fi dell'orologio spento o su un'altra rete; la porta è cambiata (rileggila nella schermata Debug wireless) |
| L'orologio non compare più dopo il riavvio | Riattiva **Debug wireless** e rifai `adb connect` con la nuova porta (l'abbinamento resta valido) |
| `unauthorized` in `adb devices` | Accetta la richiesta sull'orologio; se non compare, disattiva e riattiva **Debug ADB** |
| `more than one device` durante l'installazione | Usa `ANDROID_SERIAL` (punto 6) |
| La fascia non viene trovata | È collegata a un altro dispositivo o app (punto 7); toglila e rimettila per riattivarla |
| La registrazione si interrompe a schermo spento | RunPro è tra le app in sospensione (punto 7, Batteria) |

## 10. A fine sessione di sviluppo

Disattiva **Debug wireless** (consuma batteria e lascia l'orologio raggiungibile sulla rete). Non usare il
debug wireless su reti pubbliche. Le Opzioni sviluppatore possono restare attive.
