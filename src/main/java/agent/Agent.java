package agent;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import reactor.core.Disposable;
import reactor.core.publisher.Hooks;

/** Uso: java -jar coding-agent.jar [directory-di-lavoro] */
@SpringBootApplication
public class Agent {

    /** Massimo numero di chiamate ai tool per ogni richiesta, e messaggi tenuti in cronologia. */
    static final int MAX_ITERAZIONI = 200, MAX_MESSAGGI = 1000;

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    static {
        // Con ESC annulliamo la richiesta HTTP mentre il client JDK sta ancora connettendo: il futuro
        // completa con ClosedChannelException su una catena già cancellata e Reactor la logga come
        // errore "droppato". È un effetto atteso dell'interruzione, non un errore dell'agente.
        Hooks.onErrorDropped(e -> {
            if (!hasCause(e, java.nio.channels.ClosedChannelException.class)) {
                log.error("Errore Reactor non gestito", e);
            }
        });
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) return true;
        }
        return false;
    }

    public static void main(String[] args) {
        SpringApplication.run(Agent.class, args);
    }

    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    @Bean
    CommandLineRunner cli(ChatClient.Builder builder, org.springframework.core.env.Environment env) {
        return args -> {
            Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
            var console = new Console();
            Runtime.getRuntime().addShutdownHook(new Thread(console::restore));
            var cancel = new Cancel();
            boolean auto = env.getProperty("AGENT_AUTO", Boolean.class, false);
            boolean showReasoning = env.getProperty("AGENT_REASONING", Boolean.class, false);
            var reasoning = new ReasoningPrinter(showReasoning);
            var tools = new Tools(root, console, new int[1], MAX_ITERAZIONI, !auto, cancel, reasoning::end);
            var memory = MessageWindowChatMemory.builder().maxMessages(MAX_MESSAGGI).build();
            var chat = builder
                .defaultSystem("Sei un coding agent. Lavori nella directory " + root + " (OS: " + System.getProperty("os.name")
                    + ", shell dei comandi: " + (WINDOWS ? "cmd" : "bash") + "). Usa i tool per esplorare, "
                    + "leggere, creare e modificare file e per compilare (es. 'mvn -q compile'). Se la compilazione "
                    + "fallisce correggi e riprova. Rispondi in modo breve alla fine."
                    + (Files.exists(root.resolve("AGENT.md")) ? "\n\nIstruzioni del progetto (AGENT.md):\n" + Files.readString(root.resolve("AGENT.md")) : ""))
                .defaultTools(tools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
            System.out.println("Agent su " + root + " (exit per uscire" + (console.canInterrupt() ? ", ESC per interrompere" : "")
                + ", AGENT_AUTO=true per non chiedere conferme"
                + (showReasoning ? "" : ", AGENT_REASONING=true per vedere il reasoning") + ")");
            String conversationId = "cli";
            while (true) {
                String q = console.readLine("\n> ");
                if (q == null) break;
                q = q.trim();
                if (q.equalsIgnoreCase("exit")) break;
                if (q.isEmpty()) continue;

                List<Message> snapshot = List.copyOf(memory.get(conversationId));
                for (int i = 0; i < 6; i++) {
                    cancel.reset();
                    tools.calls[0] = 0;
                    var done = new CountDownLatch(1);
                    var error = new AtomicReference<Throwable>();
                    var tail = new AtomicReference<>("");
                    Disposable sub = chat.prompt(q)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .stream().chatResponse()
                        .doOnNext(r -> {
                            if (r == null || r.getResult() == null) return;
                            var out = r.getResult().getOutput();
                            var meta = out.getMetadata();
                            Object think = meta == null ? null : meta.get("reasoningContent");
                            if (think instanceof String s && !s.isEmpty()) reasoning.reasoning(s);
                            String text = out.getText();
                            if (text != null && !text.isEmpty()) {
                                reasoning.content();
                                System.out.print(text);
                                System.out.flush();
                                tail.set(text);
                            }
                        })
                        .doFinally(signal -> done.countDown())
                        .subscribe(v -> { }, error::set);
                    console.watch(cancel, sub::dispose);
                    try {
                        done.await();
                    } finally {
                        console.stopWatching();
                    }
                    boolean hadReasoning = reasoning.end();
                    if (cancel.isCancelled()) {
                        sub.dispose();
                        if (!hadReasoning && !tail.get().endsWith("\n")) System.out.println();
                        System.out.println("[interrotto]");
                        restore(memory, conversationId, snapshot);
                        break;
                    }
                    Throwable e = error.get();
                    if (e == null) {
                        if (!hadReasoning && !tail.get().endsWith("\n")) System.out.println();
                        break;
                    }
                    boolean limite = String.valueOf(e.getMessage()).contains("429");
                    restore(memory, conversationId, snapshot);
                    System.out.println(limite ? "  rate limit, riprovo tra 15s..." : "Errore: " + e.getMessage());
                    if (!limite) break;
                    Thread.sleep(15000);
                }
            }
            console.restore();
        };
    }

    private static void restore(ChatMemory memory, String conversationId, List<Message> snapshot) {
        memory.clear(conversationId);
        if (!snapshot.isEmpty()) memory.add(conversationId, snapshot);
    }

    /** Mostra in streaming il reasoning del modello (delta.reasoning_content), in corsivo se abilitato. */
    static final class ReasoningPrinter {
        private static final boolean ANSI = System.console() != null;
        private final boolean enabled;
        private volatile boolean open;

        ReasoningPrinter(boolean enabled) {
            this.enabled = enabled;
        }

        void reasoning(String s) {
            if (!enabled) return;
            if (!open) {
                if (ANSI) System.out.print("\u001b[3m");
                System.out.print("[reasoning] ");
                open = true;
            }
            System.out.print(s);
            System.out.flush();
        }

        void content() {
            if (enabled && open) {
                if (ANSI) System.out.print("\u001b[0m");
                System.out.print("\n[risposta] ");
                open = false;
            }
        }

        /** Chiude il blocco di reasoning, se aperto. Ritorna true se ha stampato qualcosa. */
        boolean end() {
            if (!enabled || !open) return false;
            if (ANSI) System.out.print("\u001b[0m");
            System.out.println();
            open = false;
            return true;
        }
    }

    /** Flag condiviso tra watcher della tastiera e tool, così l'interruzione ferma anche i comandi in corso. */
    static final class Cancel {
        private volatile boolean cancelled;

        boolean isCancelled() { return cancelled; }

        void cancel() { cancelled = true; }

        void reset() { cancelled = false; }
    }

    /**
     * Input da terminale. Su Unix mette la tastiera in modalità raw solo mentre il modello lavora,
     * così ESC arriva subito; quando serve leggere una riga (prompt o conferma) ripristina la modalità
     * normale. Se stdin non è un terminale, ESC non è disponibile e si comporta come prima.
     */
    static final class Console {
        private final Object lock = new Object();
        private final String saved;
        private Thread watcher;
        private volatile boolean running, paused, reading;

        Console() {
            this.saved = stty("-g");
        }

        boolean canInterrupt() {
            return saved != null;
        }

        private static String stty(String... args) {
            try {
                var cmd = new java.util.ArrayList<String>(List.of("stty"));
                cmd.addAll(List.of(args));
                Process p = new ProcessBuilder(cmd)
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
                String out = new String(p.getInputStream().readAllBytes(), Charset.defaultCharset()).trim();
                return p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : null;
            } catch (Exception e) {
                return null;
            }
        }

        private void setRaw() {
            if (saved != null) stty("-icanon", "min", "1", "time", "0", "-echo");
        }

        void restore() {
            if (saved != null) stty(saved);
        }

        /** Legge una riga (prompt principale o conferma). Sospende il watcher di ESC mentre legge. */
        String readLine(String prompt) {
            boolean watching, prevPaused;
            synchronized (lock) {
                watching = running;
                prevPaused = paused;
                paused = true;
                while (reading) {
                    try { lock.wait(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            }
            if (watching) restore();
            try {
                System.out.print(prompt);
                System.out.flush();
                var buf = new java.io.ByteArrayOutputStream();
                int c;
                while ((c = System.in.read()) != -1) {
                    if (c == '\n') break;
                    if (c != '\r') buf.write(c);
                }
                return c == -1 && buf.size() == 0 ? null : buf.toString(Charset.defaultCharset());
            } catch (IOException e) {
                return null;
            } finally {
                if (watching) setRaw();
                synchronized (lock) {
                    paused = prevPaused;
                    lock.notifyAll();
                }
            }
        }

        /** Avvia il watcher della tastiera: alla pressione di ESC chiama onCancel. */
        void watch(Cancel cancel, Runnable onCancel) {
            if (saved == null) return;
            synchronized (lock) {
                paused = false;
                running = true;
            }
            setRaw();
            Thread t = new Thread(() -> {
                try {
                    while (running) {
                        synchronized (lock) {
                            while (paused && running) lock.wait(50);
                        }
                        if (!running) break;
                        if (System.in.available() == 0) {
                            Thread.sleep(20);
                            continue;
                        }
                        int c;
                        synchronized (lock) {
                            if (paused || System.in.available() == 0) continue;
                            reading = true;
                            try { c = System.in.read(); } finally { reading = false; lock.notifyAll(); }
                        }
                        if (c == 27) {
                            cancel.cancel();
                            onCancel.run();
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    // terminale chiuso o interrotto: il chiamante ripristina comunque lo stato
                }
            }, "esc-watcher");
            t.setDaemon(true);
            watcher = t;
            t.start();
        }

        void stopWatching() {
            Thread t;
            synchronized (lock) {
                running = false;
                t = watcher;
                watcher = null;
                lock.notifyAll();
            }
            if (t != null) {
                t.interrupt();
                try { t.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            restore();
            synchronized (lock) {
                paused = false;
            }
        }
    }

    record Tools(Path root, Console console, int[] calls, int maxCalls, boolean confirm, Cancel cancel, Runnable beforeTool) {

        /** Ritorna un messaggio di errore se l'utente ha interrotto o il limite di chiamate è superato, altrimenti null. */
        private String limit() {
            if (cancel.isCancelled()) return "interrotto dall'utente";
            return ++calls[0] > maxCalls ? "ERRORE: limite di " + maxCalls + " chiamate ai tool raggiunto, fermati e rispondi" : null;
        }

        private void begin(String name, String detail) {
            beforeTool.run();
            System.out.println("  [" + name + "] " + detail);
        }

        private static String safe(java.util.concurrent.Callable<String> c) {
            try { return c.call(); } catch (Exception e) { return "ERRORE: " + e; }
        }

        private Path p(String f) {
            Path r = root.resolve(f).normalize();
            if (!r.startsWith(root)) throw new IllegalArgumentException("fuori dalla directory");
            return r;
        }

        @Tool(description = "Elenca ricorsivamente i file di una directory (relativa)")
        String listFiles(@ToolParam(description = "directory relativa, '.' per la root") String dir) {
            begin("listFiles", dir);
            String err = limit();
            if (err != null) return err;
            return safe(() -> {
                if (!Files.isDirectory(p(dir))) return "la directory non esiste";
                try (Stream<Path> s = Files.walk(p(dir))) {
                    return s.filter(Files::isRegularFile)
                        .map(x -> root.relativize(x).toString().replace('\\', '/'))
                        .filter(x -> !x.startsWith(".git") && !x.startsWith("target"))
                        .takeWhile(x -> !cancel.isCancelled())
                        .limit(2000).collect(Collectors.joining("\n", "", "\n(fine elenco; se vuoto non ci sono file: crea pure i file)"));
                }
            });
        }

        @Tool(description = "Legge il contenuto di un file")
        String readFile(@ToolParam(description = "percorso relativo") String path) {
            begin("readFile", path);
            String err = limit();
            if (err != null) return err;
            return safe(() -> Files.readString(p(path)));
        }

        @Tool(description = "Crea o sovrascrive un file con il contenuto completo (crea le directory mancanti)")
        String writeFile(@ToolParam(description = "percorso relativo") String path,
                         @ToolParam(description = "contenuto completo") String content) {
            begin("writeFile", path);
            String err = limit();
            if (err != null) return err;
            return safe(() -> {
                Files.createDirectories(p(path).getParent());
                Files.writeString(p(path), content);
                return "ok";
            });
        }

        @Tool(description = "Modifica un file sostituendo un testo esatto (old deve comparire una volta sola)")
        String editFile(String path, String old, String replacement) {
            begin("editFile", path);
            String err = limit();
            if (err != null) return err;
            return safe(() -> {
                String c = Files.readString(p(path));
                if (c.split(java.util.regex.Pattern.quote(old), -1).length != 2) return "errore: 'old' assente o non unico";
                Files.writeString(p(path), c.replace(old, replacement));
                return "ok";
            });
        }

        @Tool(description = "Esegue un comando shell nella root (es. mvn -q compile) e restituisce exit code e output")
        String runCommand(String command) {
            begin("runCommand", command);
            String err = limit();
            if (err != null) return err;
            if (confirm) {
                String answer = console.readLine("  Eseguire? [s/N] ");
                if (answer == null || !answer.trim().toLowerCase().startsWith("s")) return "comando rifiutato dall'utente";
            }
            if (cancel.isCancelled()) return "interrotto dall'utente";
            return safe(() -> {
                Process pr = new ProcessBuilder(WINDOWS ? "cmd" : "bash", WINDOWS ? "/c" : "-c", command).directory(root.toFile())
                    .redirectErrorStream(true).start();
                var out = new StringBuilder();
                Thread reader = new Thread(() -> {
                    try (var r = new InputStreamReader(pr.getInputStream(), Charset.defaultCharset())) {
                        char[] buf = new char[8192];
                        int n;
                        while ((n = r.read(buf)) != -1) synchronized (out) { out.append(buf, 0, n); }
                    } catch (IOException ignored) {
                        // processo terminato
                    }
                });
                reader.setDaemon(true);
                reader.start();
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
                String status = null;
                while (!pr.waitFor(100, TimeUnit.MILLISECONDS)) {
                    if (cancel.isCancelled()) { status = "interrotto"; break; }
                    if (System.nanoTime() > deadline) { status = "timeout"; break; }
                }
                if (status != null) pr.destroyForcibly();
                pr.waitFor(2, TimeUnit.SECONDS);
                reader.join(1000);
                String text;
                synchronized (out) { text = out.toString(); }
                String code = status != null ? status : String.valueOf(pr.exitValue());
                return "exit=" + code + "\n" + (text.length() > 50000 ? text.substring(text.length() - 50000) : text);
            });
        }

        @Tool(description = "Cerca un testo (regex Java, case-insensitive) nei file di testo sotto una directory; restituisce 'file:riga: testo' con 2 righe di contesto ('file-riga- testo')")
        String searchInFiles(@ToolParam(description = "espressione regolare") String pattern,
                             @ToolParam(description = "directory relativa, '.' per la root") String dir,
                             @ToolParam(description = "glob sul nome file, es. '*.java'; vuoto per tutti") String glob) {
            begin("searchInFiles", pattern + " in " + dir + " " + glob);
            String err = limit();
            if (err != null) return err;
            return safe(() -> {
                var re = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
                var gm = FileSystems.getDefault().getPathMatcher("glob:" + (glob == null || glob.isBlank() ? "*" : glob));
                var out = new java.util.ArrayList<String>();
                int n = 0;
                try (Stream<Path> s = Files.walk(p(dir))) {
                    for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                        if (cancel.isCancelled()) break;
                        String rel = root.relativize(f).toString().replace('\\', '/');
                        if (rel.matches("(.*/)?(\\.git|\\.idea|target|build|node_modules|generated)/.*") || !gm.matches(f.getFileName())) continue;
                        java.util.List<String> lines;
                        try { lines = Files.readAllLines(f); } catch (IOException e) { continue; } // binario
                        int last = -1;
                        for (int i = 0; i < lines.size() && n < 500; i++) {
                            if (!re.matcher(lines.get(i)).find()) continue;
                            n++;
                            if (i - 2 > last + 1 && last >= 0) out.add("--");
                            for (int j = Math.max(last + 1, i - 2); j <= Math.min(lines.size() - 1, i + 2); j++)
                                out.add(rel + (j == i ? ":" : "-") + (j + 1) + (j == i ? ": " : "- ") + lines.get(j).strip());
                            last = Math.min(lines.size() - 1, i + 2);
                        }
                    }
                }
                return n == 0 ? "nessun risultato" : String.join("\n", out) + (n >= 500 ? "\n(troncato a 500 risultati)" : "");
            });
        }

        @Tool(description = "Mostra le modifiche non ancora committate (git diff HEAD), opzionalmente per un solo file")
        String gitDiff(@ToolParam(description = "file relativo, vuoto per tutto") String path) {
            begin("gitDiff", path);
            String err = limit();
            if (err != null) return err;
            return safe(() -> {
                var cmd = new java.util.ArrayList<>(java.util.List.of("git", "diff", "HEAD"));
                if (path != null && !path.isBlank()) { cmd.add("--"); cmd.add(root.relativize(p(path)).toString()); }
                Process pr = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();
                String out = new String(pr.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
                pr.waitFor();
                return out.isBlank() ? "nessuna modifica" : out.length() > 50000 ? out.substring(0, 50000) + "\n(troncato)" : out;
            });
        }
    }
}
