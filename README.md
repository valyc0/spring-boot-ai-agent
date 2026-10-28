# Java Coding Agent

Un coding agent da riga di comando, scritto in Java con **Spring Boot** e **Spring AI**, che lavora come
un agente "vero": gli dai un compito in linguaggio naturale, lui esplora una directory, legge, crea e
modifica file, lancia comandi (ad esempio `mvn compile`), legge gli errori e corregge.

Pensato per ambienti aziendali senza Internet: servono solo un endpoint LLM compatibile con l'API OpenAI
(Ollama, DeepSeek, Groq, OpenRouter, ...) e le dipendenze Maven disponibili su Nexus.

## Come funziona

```
Tu (CLI) ──► ChatClient (Spring AI) ──► LLM (API OpenAI-compatible)
                  ▲   │ "chiama il tool X con questi argomenti"
                  │   ▼
                  └── Tools Java: listFiles, readFile, writeFile, editFile, searchInFiles, gitDiff, runCommand
```

1. Scrivi una richiesta nel terminale.
2. Il modello decide quale tool usare; Spring AI esegue il metodo Java corrispondente e restituisce il
   risultato al modello. La risposta arriva in streaming, quindi vedi il testo mentre viene generato.
3. Il ciclo si ripete (esplora, scrive, compila, corregge) finché il modello risponde senza chiamare altri tool.
4. La conversazione resta in memoria, quindi puoi fare richieste successive ("ora aggiungi un test").

Mentre l'agente sta ragionando o eseguendo un comando, premi **ESC** per interromperlo: la richiesta
viene annullata (anche il comando shell in corso) e torni subito al prompt. Il turno interrotto non viene
salvato in cronologia. L'interruzione con ESC richiede un terminale Unix; se stdin non è un TTY (o su
Windows) il tasto non è disponibile e si può usare Ctrl+C per terminare il processo.

Tutto il codice è in [`src/main/java/agent/Agent.java`](src/main/java/agent/Agent.java).

## Tool a disposizione del modello

| Tool | Cosa fa |
|------|---------|
| `listFiles` | Elenca i file di una directory (ricorsivo, ignora `.git` e `target`) |
| `readFile` | Legge un file |
| `writeFile` | Crea o sovrascrive un file (crea le directory mancanti) |
| `editFile` | Sostituisce un testo esatto in un file esistente |
| `searchInFiles` | Cerca una regex (case-insensitive) nei file di testo sotto una directory, con filtro glob (`*.java`) e 2 righe di contesto (`file:riga: testo`, max 500 risultati; ignora `.git`, `.idea`, `target`, `build`, `node_modules`, `generated`) |
| `gitDiff` | Mostra le modifiche non committate (`git diff HEAD`), anche per un solo file |
| `runCommand` | Esegue un comando shell nella directory di lavoro (timeout 5 min, output limitato a 50000 caratteri). **Chiede conferma `[s/N]`** prima di eseguire; con `AGENT_AUTO=true` non chiede |

Gli errori dei tool (file inesistente, ecc.) vengono restituiti al modello come testo, così può reagire.

## Requisiti

- Java 21
- Maven 3.8+
- Un endpoint LLM compatibile OpenAI **con supporto al tool calling**

## Configurazione

Copia `.env.example` in `.env` nella directory da cui lanci l'agente e imposta:

```
LLM_URL=https://openrouter.ai/api
LLM_MODEL=cohere/north-mini-code:free
LLM_KEY=la-tua-chiave
```

| Variabile | Significato |
|-----------|-------------|
| `LLM_URL` | URL base **senza** `/v1`: Spring AI aggiunge `/v1/chat/completions` |
| `LLM_MODEL` | Nome del modello |
| `LLM_KEY` | API key (qualsiasi valore se l'endpoint non la richiede, es. Ollama) |

Esempi di URL: Ollama `http://host:11434`, Groq `https://api.groq.com/openai`, OpenRouter `https://openrouter.ai/api`.
Le variabili possono essere anche variabili d'ambiente. Il file `.env` è escluso da git.

## Build e avvio

```bash
mvn package -DskipTests
java -jar target/coding-agent-0.1.jar /percorso/del/progetto
```

Oppure, per avviarlo con Maven e `AGENT_AUTO=true` (nessuna conferma per `runCommand`):

```bash
./run.sh /percorso/del/progetto
```

Esempio di sessione:

```
> crea una classe main che legge un csv
  [listFiles] .
  [writeFile] pom.xml
  [writeFile] src/main/java/com/example/App.java
  [runCommand] mvn -q compile
La classe App è stata creata e il progetto compila.

> exit
```

L'argomento è la directory su cui l'agente può lavorare (default: directory corrente). Ogni tool vede solo
quella directory: i percorsi che ne escono vengono rifiutati.

## AGENT.md

Se nella directory di lavoro esiste un file `AGENT.md`, il suo contenuto viene aggiunto al system prompt all'avvio:
usalo per convenzioni, struttura dei package, comandi di build e test del progetto.

## Limiti e note di sicurezza

- `runCommand` esegue **qualsiasi** comando (dopo la tua conferma, salvo `AGENT_AUTO=true`): usalo su directory e macchine di cui ti fidi.
  Meglio lavorare su un repository git per poter vedere (`git diff`) e annullare le modifiche.
- Se il modello non supporta il tool calling, l'agente non riesce a fare nulla di utile.
- Su provider con limiti di token al minuto (es. Groq gratuito) l'agente, in caso di errore 429, aspetta 15 secondi e riprova (max 6 volte).
- Ogni richiesta può fare al massimo 200 chiamate ai tool (`MAX_ITERAZIONI`); la cronologia tiene gli ultimi 1000 messaggi (`MAX_MESSAGGI`), i più vecchi vengono scartati.
- Versioni usate: Spring Boot 3.5.6, Spring AI 1.0.7; allineale a quelle disponibili sul tuo Nexus.

## Idee per evolverlo

Riassunto vero della cronologia (invece della finestra fissa), conferma anche per `writeFile`/`editFile`, limiti configurabili da `.env`.
