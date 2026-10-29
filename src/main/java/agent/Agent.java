package agent;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;
import java.util.stream.*;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.*;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.annotation.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import reactor.core.Disposable;
import reactor.core.publisher.Hooks;

/** Uso: java -jar coding-agent.jar [directory-di-lavoro] */
@SpringBootApplication
public class Agent {
    /** Massimo numero di chiamate ai tool per ogni richiesta, e messaggi tenuti in cronologia. */
    static final int MAX_ITERAZIONI = 200, MAX_MESSAGGI = 1000;
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    static {
        // Con ESC annulliamo la richiesta HTTP mentre il client JDK sta ancora connettendo: Reactor logga la
        // ClosedChannelException come errore "droppato". È un effetto atteso, non un errore dell'agente.
        Hooks.onErrorDropped(e -> {
            for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause())
                if (t instanceof java.nio.channels.ClosedChannelException) return;
            LoggerFactory.getLogger(Agent.class).error("Errore Reactor non gestito", e);
        });
    }

    public static void main(String[] args) { SpringApplication.run(Agent.class, args); }

    @Bean
    CommandLineRunner cli(ChatClient.Builder builder, Environment env) {
        return args -> {
            Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize(), md = root.resolve("AGENT.md");
            var console = new Console();
            Runtime.getRuntime().addShutdownHook(new Thread(console::restore));
            var cancel = new AtomicBoolean();
            boolean auto = env.getProperty("AGENT_AUTO", Boolean.class, false), showReasoning = env.getProperty("AGENT_REASONING", Boolean.class, false);
            var reasoning = new ReasoningPrinter(showReasoning);
            var tools = new Tools(root, console, new int[1], !auto, cancel, reasoning::end);
            var memory = MessageWindowChatMemory.builder().maxMessages(MAX_MESSAGGI).build();
            var chat = builder
                .defaultSystem("Sei un coding agent. Lavori nella directory " + root + " (OS: " + System.getProperty("os.name")
                    + ", shell dei comandi: " + (WINDOWS ? "cmd" : "bash") + "). Usa i tool per esplorare, "
                    + "leggere, creare e modificare file e per compilare (es. 'mvn -q compile'). Se la compilazione "
                    + "fallisce correggi e riprova. Rispondi in modo breve alla fine."
                    + (Files.exists(md) ? "\n\nIstruzioni del progetto (AGENT.md):\n" + Files.readString(md) : ""))
                .defaultTools(tools).defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build();
            System.out.println("Agent su " + root + " (exit per uscire" + (console.canInterrupt() ? ", ESC per interrompere" : "")
                + ", AGENT_AUTO=true per non chiedere conferme" + (showReasoning ? "" : ", AGENT_REASONING=true per vedere il reasoning") + ")");
            String id = "cli";
            for (String q; (q = console.readLine("\n> ")) != null && !(q = q.trim()).equalsIgnoreCase("exit"); ) {
                if (q.isEmpty()) continue;
                List<Message> snapshot = List.copyOf(memory.get(id));
                for (int i = 0; i < 6; i++) {
                    cancel.set(false);
                    tools.calls[0] = 0;
                    var done = new CountDownLatch(1);
                    var error = new AtomicReference<Throwable>();
                    var tail = new AtomicReference<>("");
                    Disposable sub = chat.prompt(q).advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id)).stream().chatResponse()
                        .doOnNext(r -> {
                            if (r == null || r.getResult() == null) return;
                            var out = r.getResult().getOutput();
                            if (out.getMetadata() != null && out.getMetadata().get("reasoningContent") instanceof String s && !s.isEmpty()) reasoning.reasoning(s);
                            String text = out.getText();
                            if (text != null && !text.isEmpty()) { reasoning.content(); System.out.print(text); System.out.flush(); tail.set(text); }
                        })
                        .doFinally(signal -> done.countDown()).subscribe(v -> { }, error::set);
                    console.watch(cancel, sub::dispose);
                    try { done.await(); } finally { console.stopWatching(); }
                    boolean hadReasoning = reasoning.end(), cancelled = cancel.get();
                    Throwable e = error.get();
                    if (cancelled) sub.dispose();
                    if (cancelled || e == null) {
                        if (!hadReasoning && !tail.get().endsWith("\n")) System.out.println();
                        if (cancelled) { System.out.println("[interrotto]"); restore(memory, id, snapshot); }
                        break;
                    }
                    boolean limite = String.valueOf(e.getMessage()).contains("429");
                    restore(memory, id, snapshot);
                    System.out.println(limite ? "  rate limit, riprovo tra 15s..." : "Errore: " + e.getMessage());
                    if (!limite) break;
                    Thread.sleep(15000);
                }
            }
            console.restore();
        };
    }

    static void restore(ChatMemory memory, String id, List<Message> snapshot) {
        memory.clear(id);
        if (!snapshot.isEmpty()) memory.add(id, snapshot);
    }

    /** Mostra in streaming il reasoning del modello (delta.reasoning_content), in corsivo se abilitato. */
    static final class ReasoningPrinter {
        static final boolean ANSI = System.console() != null;
        final boolean enabled;
        volatile boolean open;

        ReasoningPrinter(boolean enabled) { this.enabled = enabled; }

        void reasoning(String s) {
            if (!enabled) return;
            if (!open) { System.out.print((ANSI ? "\u001b[3m" : "") + "[reasoning] "); open = true; }
            System.out.print(s);
            System.out.flush();
        }

        void content() {
            if (enabled && open) { System.out.print((ANSI ? "\u001b[0m" : "") + "\n[risposta] "); open = false; }
        }

        /** Chiude il blocco di reasoning, se aperto. Ritorna true se ha stampato qualcosa. */
        boolean end() {
            if (!enabled || !open) return false;
            System.out.println(ANSI ? "\u001b[0m" : "");
            open = false;
            return true;
        }
    }

    /**
     * Input da terminale. Su Unix mette la tastiera in modalità raw solo mentre il modello lavora, così ESC
     * arriva subito; per leggere una riga (prompt o conferma) ripristina la modalità normale.
     * Se stdin non è un terminale, ESC non è disponibile.
     */
    static final class Console {
        final Object lock = new Object();
        final String saved = stty("-g");
        Thread watcher;
        volatile boolean running, paused, reading;

        boolean canInterrupt() { return saved != null; }
        void setRaw() { if (saved != null) stty("-icanon", "min", "1", "time", "0", "-echo"); }
        void restore() { if (saved != null) stty(saved); }

        static String stty(String... args) {
            try {
                Process p = new ProcessBuilder(Stream.concat(Stream.of("stty"), Stream.of(args)).toList())
                    .redirectInput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                String out = new String(p.getInputStream().readAllBytes(), Charset.defaultCharset()).trim();
                return p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : null;
            } catch (Exception e) { return null; }
        }

        /** Legge una riga (prompt principale o conferma). Sospende il watcher di ESC mentre legge. */
        String readLine(String prompt) {
            boolean watching, prevPaused;
            synchronized (lock) {
                watching = running;
                prevPaused = paused;
                paused = true;
                while (reading) try { lock.wait(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (watching) restore();
            try {
                System.out.print(prompt);
                System.out.flush();
                var buf = new ByteArrayOutputStream();
                int c;
                while ((c = System.in.read()) != -1 && c != '\n') if (c != '\r') buf.write(c);
                return c == -1 && buf.size() == 0 ? null : buf.toString(Charset.defaultCharset());
            } catch (IOException e) {
                return null;
            } finally {
                if (watching) setRaw();
                synchronized (lock) { paused = prevPaused; lock.notifyAll(); }
            }
        }

        /** Avvia il watcher della tastiera: alla pressione di ESC chiama onCancel. */
        void watch(AtomicBoolean cancel, Runnable onCancel) {
            if (saved == null) return;
            synchronized (lock) { paused = false; running = true; }
            setRaw();
            watcher = Thread.ofPlatform().name("esc-watcher").daemon().start(() -> {
                try {
                    while (running) {
                        synchronized (lock) { while (paused && running) lock.wait(50); }
                        if (!running) break;
                        if (System.in.available() == 0) { Thread.sleep(20); continue; }
                        int c;
                        synchronized (lock) {
                            if (paused || System.in.available() == 0) continue;
                            reading = true;
                            try { c = System.in.read(); } finally { reading = false; lock.notifyAll(); }
                        }
                        if (c == 27) { cancel.set(true); onCancel.run(); break; }
                    }
                } catch (Exception ignored) { } // terminale chiuso o interrotto: il chiamante ripristina comunque lo stato
            });
        }

        void stopWatching() {
            Thread t;
            synchronized (lock) { running = false; t = watcher; watcher = null; lock.notifyAll(); }
            if (t != null) {
                t.interrupt();
                try { t.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            restore();
            synchronized (lock) { paused = false; }
        }
    }

    /** cancel è condiviso con il watcher della tastiera, così l'interruzione ferma anche i comandi in corso. */
    record Tools(Path root, Console console, int[] calls, boolean confirm, AtomicBoolean cancel, Runnable beforeTool) {

        /** Stampa la chiamata, controlla interruzione e limite di chiamate, poi esegue l'azione convertendo le eccezioni. */
        private String run(String name, String detail, Callable<String> action) {
            beforeTool.run();
            System.out.println("  [" + name + "] " + detail);
            if (cancel.get()) return "interrotto dall'utente";
            if (++calls[0] > MAX_ITERAZIONI) return "ERRORE: limite di " + MAX_ITERAZIONI + " chiamate ai tool raggiunto, fermati e rispondi";
            try { return action.call(); } catch (Exception e) { return "ERRORE: " + e; }
        }

        private Path p(String f) {
            Path r = root.resolve(f).normalize();
            if (!r.startsWith(root)) throw new IllegalArgumentException("fuori dalla directory");
            return r;
        }

        private String rel(Path f) { return root.relativize(f).toString().replace('\\', '/'); }

        @Tool(description = "Elenca ricorsivamente i file di una directory (relativa)")
        String listFiles(@ToolParam(description = "directory relativa, '.' per la root") String dir) {
            return run("listFiles", dir, () -> {
                if (!Files.isDirectory(p(dir))) return "la directory non esiste";
                try (Stream<Path> s = Files.walk(p(dir))) {
                    return s.filter(Files::isRegularFile).map(this::rel).filter(x -> !x.startsWith(".git") && !x.startsWith("target"))
                        .takeWhile(x -> !cancel.get()).limit(2000)
                        .collect(Collectors.joining("\n", "", "\n(fine elenco; se vuoto non ci sono file: crea pure i file)"));
                }
            });
        }

        @Tool(description = "Legge il contenuto di un file")
        String readFile(@ToolParam(description = "percorso relativo") String path) {
            return run("readFile", path, () -> Files.readString(p(path)));
        }

        @Tool(description = "Crea o sovrascrive un file con il contenuto completo (crea le directory mancanti)")
        String writeFile(@ToolParam(description = "percorso relativo") String path, @ToolParam(description = "contenuto completo") String content) {
            return run("writeFile", path, () -> {
                Files.createDirectories(p(path).getParent());
                Files.writeString(p(path), content);
                return "ok";
            });
        }

        @Tool(description = "Modifica un file sostituendo un testo esatto (old deve comparire una volta sola)")
        String editFile(String path, String old, String replacement) {
            return run("editFile", path, () -> {
                String c = Files.readString(p(path));
                if (c.split(Pattern.quote(old), -1).length != 2) return "errore: 'old' assente o non unico";
                Files.writeString(p(path), c.replace(old, replacement));
                return "ok";
            });
        }

        @Tool(description = "Esegue un comando shell nella root (es. mvn -q compile) e restituisce exit code e output")
        String runCommand(String command) {
            return run("runCommand", command, () -> {
                if (confirm) {
                    String answer = console.readLine("  Eseguire? [s/N] ");
                    if (answer == null || !answer.trim().toLowerCase().startsWith("s")) return "comando rifiutato dall'utente";
                }
                if (cancel.get()) return "interrotto dall'utente";
                Process pr = new ProcessBuilder(WINDOWS ? "cmd" : "bash", WINDOWS ? "/c" : "-c", command).directory(root.toFile())
                    .redirectErrorStream(true).start();
                var out = new StringBuffer();
                Thread reader = Thread.ofPlatform().daemon().start(() -> {
                    try (var r = new InputStreamReader(pr.getInputStream(), Charset.defaultCharset())) {
                        char[] buf = new char[8192];
                        for (int n; (n = r.read(buf)) != -1; ) out.append(buf, 0, n);
                    } catch (IOException ignored) { } // processo terminato
                });
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
                String status = null;
                while (status == null && !pr.waitFor(100, TimeUnit.MILLISECONDS))
                    status = cancel.get() ? "interrotto" : System.nanoTime() > deadline ? "timeout" : null;
                if (status != null) pr.destroyForcibly();
                pr.waitFor(2, TimeUnit.SECONDS);
                reader.join(1000);
                String text = out.toString();
                return "exit=" + (status != null ? status : pr.exitValue()) + "\n" + (text.length() > 50000 ? text.substring(text.length() - 50000) : text);
            });
        }

        @Tool(description = "Cerca un testo (regex Java, case-insensitive) nei file di testo sotto una directory; restituisce 'file:riga: testo' con 2 righe di contesto ('file-riga- testo')")
        String searchInFiles(@ToolParam(description = "espressione regolare") String pattern,
                             @ToolParam(description = "directory relativa, '.' per la root") String dir,
                             @ToolParam(description = "glob sul nome file, es. '*.java'; vuoto per tutti") String glob) {
            return run("searchInFiles", pattern + " in " + dir + " " + glob, () -> {
                var re = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                var gm = FileSystems.getDefault().getPathMatcher("glob:" + (glob == null || glob.isBlank() ? "*" : glob));
                var out = new ArrayList<String>();
                int n = 0;
                try (Stream<Path> s = Files.walk(p(dir))) {
                    for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                        if (cancel.get()) break;
                        String name = rel(f);
                        if (name.matches("(.*/)?(\\.git|\\.idea|target|build|node_modules|generated)/.*") || !gm.matches(f.getFileName())) continue;
                        List<String> lines;
                        try { lines = Files.readAllLines(f); } catch (IOException e) { continue; } // binario
                        for (int i = 0, last = -1; i < lines.size() && n < 500; i++) {
                            if (!re.matcher(lines.get(i)).find()) continue;
                            n++;
                            if (i - 2 > last + 1 && last >= 0) out.add("--");
                            int end = Math.min(lines.size() - 1, i + 2);
                            for (int j = Math.max(last + 1, i - 2); j <= end; j++)
                                out.add(name + (j == i ? ":" : "-") + (j + 1) + (j == i ? ": " : "- ") + lines.get(j).strip());
                            last = end;
                        }
                    }
                }
                return n == 0 ? "nessun risultato" : String.join("\n", out) + (n >= 500 ? "\n(troncato a 500 risultati)" : "");
            });
        }

        @Tool(description = "Mostra le modifiche non ancora committate (git diff HEAD), opzionalmente per un solo file")
        String gitDiff(@ToolParam(description = "file relativo, vuoto per tutto") String path) {
            return run("gitDiff", path, () -> {
                var cmd = new ArrayList<>(List.of("git", "diff", "HEAD"));
                if (path != null && !path.isBlank()) cmd.addAll(List.of("--", root.relativize(p(path)).toString()));
                Process pr = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();
                String out = new String(pr.getInputStream().readAllBytes(), Charset.defaultCharset());
                pr.waitFor();
                return out.isBlank() ? "nessuna modifica" : out.length() > 50000 ? out.substring(0, 50000) + "\n(troncato)" : out;
            });
        }
    }
}
