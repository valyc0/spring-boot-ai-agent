package agent;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.*;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Uso: java -jar coding-agent.jar [directory-di-lavoro] */
@SpringBootApplication(proxyBeanMethods = false)
public class Agent implements CommandLineRunner {

    static final int MAX_ITERAZIONI = 200, MAX_MESSAGGI = 1000;
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    final ChatClient.Builder builder;
    final Scanner in = new Scanner(System.in);
    Path root;
    int calls, exit;
    boolean confirm = !"true".equalsIgnoreCase(System.getenv("AGENT_AUTO"));

    Agent(ChatClient.Builder builder) { this.builder = builder; }

    public static void main(String[] args) { SpringApplication.run(Agent.class, args); }

    public void run(String... args) throws Exception {
        root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path md = root.resolve("AGENT.md");
        var chat = builder
            .defaultSystem("Sei un coding agent. Lavori nella directory " + root + " (OS: " + System.getProperty("os.name")
                + ", shell dei comandi: " + (WINDOWS ? "cmd" : "bash") + "). Usa i tool per esplorare, "
                + "leggere, creare e modificare file e per compilare (es. 'mvn -q compile'). Se la compilazione "
                + "fallisce correggi e riprova. Rispondi in modo breve alla fine."
                + (Files.exists(md) ? "\n\nIstruzioni del progetto (AGENT.md):\n" + Files.readString(md) : ""))
            .defaultTools(this)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().maxMessages(MAX_MESSAGGI).build()).build())
            .build();
        System.out.println("Agent su " + root + " (exit per uscire, AGENT_AUTO=true per non chiedere conferme)");
        while (true) {
            System.out.print("\n> ");
            if (!in.hasNextLine()) break;
            String q = in.nextLine().trim();
            if (q.equals("exit")) break;
            if (q.isEmpty()) continue;
            calls = 0;
            for (int i = 0; i < 6; i++) {
                try {
                    System.out.println(chat.prompt(q).advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "cli")).call().content());
                    break;
                } catch (Exception e) {
                    boolean limite = String.valueOf(e.getMessage()).contains("429");
                    System.out.println(limite ? "  rate limit, riprovo tra 15s..." : "Errore: " + e.getMessage());
                    if (!limite) break;
                    Thread.sleep(15000);
                }
            }
        }
    }

    /** Logga la chiamata, applica il limite di chiamate e converte le eccezioni in messaggi di errore. */
    String go(String tool, Object arg, Callable<String> c) {
        System.out.println("  [" + tool + "] " + arg);
        if (++calls > MAX_ITERAZIONI) return "ERRORE: limite di " + MAX_ITERAZIONI + " chiamate ai tool raggiunto, fermati e rispondi";
        try { return c.call(); } catch (Exception e) { return "ERRORE: " + e; }
    }

    Path p(String f) {
        Path r = root.resolve(f).normalize();
        if (!r.startsWith(root)) throw new IllegalArgumentException("fuori dalla directory");
        return r;
    }

    String rel(Path f) { return root.relativize(f).toString().replace('\\', '/'); }

    /** Esegue un processo nella root; ritorna l'output e imposta exit. */
    String exec(String... cmd) throws Exception {
        Process pr = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();
        String out = new String(pr.getInputStream().readAllBytes());
        exit = pr.waitFor();
        return out;
    }

    @Tool(description = "Elenca ricorsivamente i file di una directory (relativa)")
    String listFiles(@ToolParam(description = "directory relativa, '.' per la root") String dir) {
        return go("listFiles", dir, () -> {
            if (!Files.isDirectory(p(dir))) return "la directory non esiste";
            try (Stream<Path> s = Files.walk(p(dir))) {
                return s.filter(Files::isRegularFile).map(this::rel).filter(x -> !x.startsWith(".git") && !x.startsWith("target"))
                    .limit(2000).collect(Collectors.joining("\n", "", "\n(fine elenco; se vuoto non ci sono file: crea pure i file)"));
            }
        });
    }

    @Tool(description = "Legge il contenuto di un file")
    String readFile(@ToolParam(description = "percorso relativo") String path) {
        return go("readFile", path, () -> Files.readString(p(path)));
    }

    @Tool(description = "Crea o sovrascrive un file con il contenuto completo (crea le directory mancanti)")
    String writeFile(@ToolParam(description = "percorso relativo") String path, @ToolParam(description = "contenuto completo") String content) {
        return go("writeFile", path, () -> {
            Files.createDirectories(p(path).getParent());
            Files.writeString(p(path), content);
            return "ok";
        });
    }

    @Tool(description = "Modifica un file sostituendo un testo esatto (old deve comparire una volta sola)")
    String editFile(String path, String old, String replacement) {
        return go("editFile", path, () -> {
            String c = Files.readString(p(path));
            if (c.split(Pattern.quote(old), -1).length != 2) return "errore: 'old' assente o non unico";
            Files.writeString(p(path), c.replace(old, replacement));
            return "ok";
        });
    }

    @Tool(description = "Esegue un comando shell nella root (es. mvn -q compile) e restituisce exit code e output")
    String runCommand(String command) {
        return go("runCommand", command, () -> {
            if (confirm) {
                System.out.print("  Eseguire? [s/N] ");
                if (!in.hasNextLine() || !in.nextLine().trim().toLowerCase().startsWith("s")) return "comando rifiutato dall'utente";
            }
            String out = exec(WINDOWS ? "cmd" : "bash", WINDOWS ? "/c" : "-c", command);
            return "exit=" + exit + "\n" + (out.length() > 50000 ? out.substring(out.length() - 50000) : out);
        });
    }

    @Tool(description = "Cerca un testo (regex Java, case-insensitive) nei file di testo sotto una directory; restituisce 'file:riga: testo' con 2 righe di contesto ('file-riga- testo')")
    String searchInFiles(@ToolParam(description = "espressione regolare") String pattern,
                         @ToolParam(description = "directory relativa, '.' per la root") String dir,
                         @ToolParam(description = "glob sul nome file, es. '*.java'; vuoto per tutti") String glob) {
        return go("searchInFiles", pattern + " in " + dir + " " + glob, () -> {
            var re = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            var gm = FileSystems.getDefault().getPathMatcher("glob:" + (glob == null || glob.isBlank() ? "*" : glob));
            var out = new ArrayList<String>();
            int n = 0;
            try (Stream<Path> s = Files.walk(p(dir))) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    if (rel(f).matches("(.*/)?(\\.git|\\.idea|target|build|node_modules|generated)/.*") || !gm.matches(f.getFileName())) continue;
                    List<String> lines;
                    try { lines = Files.readAllLines(f); } catch (Exception e) { continue; } // binario
                    int last = -1;
                    for (int i = 0; i < lines.size() && n < 500; i++) {
                        if (!re.matcher(lines.get(i)).find()) continue;
                        n++;
                        if (i - 2 > last + 1 && last >= 0) out.add("--");
                        for (int j = Math.max(last + 1, i - 2); j <= Math.min(lines.size() - 1, i + 2); j++)
                            out.add(rel(f) + (j == i ? ":" : "-") + (j + 1) + (j == i ? ": " : "- ") + lines.get(j).strip());
                        last = Math.min(lines.size() - 1, i + 2);
                    }
                }
            }
            return n == 0 ? "nessun risultato" : String.join("\n", out) + (n >= 500 ? "\n(troncato a 500 risultati)" : "");
        });
    }

    @Tool(description = "Mostra le modifiche non ancora committate (git diff HEAD), opzionalmente per un solo file")
    String gitDiff(@ToolParam(description = "file relativo, vuoto per tutto") String path) {
        return go("gitDiff", path, () -> {
            boolean f = path != null && !path.isBlank();
            String out = f ? exec("git", "diff", "HEAD", "--", rel(p(path))) : exec("git", "diff", "HEAD");
            return out.isBlank() ? "nessuna modifica" : out.length() > 50000 ? out.substring(0, 50000) + "\n(troncato)" : out;
        });
    }
}
