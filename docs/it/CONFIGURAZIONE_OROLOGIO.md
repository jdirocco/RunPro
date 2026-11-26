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
RunPro controlla l'impostazione 1 e mostra **Apri impostazioni** finché manca. L'impostazione 2 non si può
leggere dall'app: RunPro te la ricorda finché non tocchi **Fatto, controllato**.

1. Sull'orologio: **Impostazioni → App → RunPro → Batteria**: scegli **Senza restrizioni** (o disattiva
   "Ottimizza utilizzo batteria").
2. Sul telefono, app **Galaxy Wearable → Impostazioni orologio → Batteria → Limiti utilizzo in background**
   (o "App in sospensione"): RunPro **non** deve essere nell'elenco delle app in sospensione o
   in sospensione profonda.

**Durante una corsa** RunPro mostra una notifica fissa e un'icona sul quadrante (Ongoing Activity) con il
tempo che scorre: toccala per tornare all'app. Se la notifica sparisce a corsa in corso, l'orologio ha
chiuso RunPro: segnalalo con una issue **Field report**.

**Posizione**: **Impostazioni → Posizione** attiva. RunPro usa solo il GPS dell'orologio.

**Fascia cardio e altri sensori Bluetooth.** Un sensore BLE accetta di solito **una sola connessione**:

1. La fascia **non** deve essere collegata al telefono, a Samsung Health o a Gear Tracker mentre corri
   con RunPro. In Samsung Health (orologio e telefono) scollega la fascia dagli **Accessori**.
2. Non abbinarla da **Impostazioni → Connessioni → Bluetooth** dell'orologio: la aggiungi da RunPro
   (Sensori → Aggiungi), che gestisce la connessione da sé. Se l'avevi abbinata lì, scegli **Dimentica**.
3. Chiudi Gear Tracker prima di correre: solo un'app alla volta può usare l'allenamento di Health Services.
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
