package agent;

import java.io.IOException;
import java.nio.file.*;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Uso: java -jar coding-agent.jar [directory-di-lavoro] */
@SpringBootApplication
public class Agent {

    /** Massimo numero di chiamate ai tool per ogni richiesta, e messaggi tenuti in cronologia. */
    static final int MAX_ITERAZIONI = 200, MAX_MESSAGGI = 1000;

    public static void main(String[] args) {
        SpringApplication.run(Agent.class, args);
    }

    @Bean
    CommandLineRunner cli(ChatClient.Builder builder) {
        return args -> {
            Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
            var in = new Scanner(System.in);
            var tools = new Tools(root, in, new int[1], MAX_ITERAZIONI, !"true".equalsIgnoreCase(System.getenv("AGENT_AUTO")));
            var chat = builder
                .defaultSystem("Sei un coding agent. Lavori nella directory " + root + ". Usa i tool per esplorare, "
                    + "leggere, creare e modificare file e per compilare (es. 'mvn -q compile'). Se la compilazione "
                    + "fallisce correggi e riprova. Rispondi in modo breve alla fine."
                    + (Files.exists(root.resolve("AGENT.md")) ? "\n\nIstruzioni del progetto (AGENT.md):\n" + Files.readString(root.resolve("AGENT.md")) : ""))
                .defaultTools(tools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().maxMessages(MAX_MESSAGGI).build()).build())
                .build();
            System.out.println("Agent su " + root + " (exit per uscire, AGENT_AUTO=true per non chiedere conferme)");
            while (true) {
                System.out.print("\n> ");
                if (!in.hasNextLine()) break;
                String q = in.nextLine().trim();
                if (q.equals("exit")) break;
                if (q.isEmpty()) continue;
                tools.calls[0] = 0;
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
        };
    }

    record Tools(Path root, Scanner in, int[] calls, int maxCalls, boolean confirm) {

        /** Ritorna un messaggio di errore se il limite di chiamate è superato, altrimenti null. */
        private String limit() {
            return ++calls[0] > maxCalls ? "ERRORE: limite di " + maxCalls + " chiamate ai tool raggiunto, fermati e rispondi" : null;
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
            System.out.println("  [listFiles] " + dir);
            if (limit() != null) return limit();
            return safe(() -> {
                if (!Files.isDirectory(p(dir))) return "la directory non esiste";
                try (Stream<Path> s = Files.walk(p(dir))) {
                    return s.filter(Files::isRegularFile)
                        .map(x -> root.relativize(x).toString())
                        .filter(x -> !x.startsWith(".git") && !x.startsWith("target"))
                        .limit(2000).collect(Collectors.joining("\n", "", "\n(fine elenco; se vuoto non ci sono file: crea pure i file)"));
                }
            });
        }

        @Tool(description = "Legge il contenuto di un file")
        String readFile(@ToolParam(description = "percorso relativo") String path) {
            System.out.println("  [readFile] " + path);
            if (limit() != null) return limit();
            return safe(() -> Files.readString(p(path)));
        }

        @Tool(description = "Crea o sovrascrive un file con il contenuto completo (crea le directory mancanti)")
        String writeFile(@ToolParam(description = "percorso relativo") String path,
                         @ToolParam(description = "contenuto completo") String content) {
            System.out.println("  [writeFile] " + path);
            if (limit() != null) return limit();
            return safe(() -> {
                Files.createDirectories(p(path).getParent());
                Files.writeString(p(path), content);
                return "ok";
            });
        }

        @Tool(description = "Modifica un file sostituendo un testo esatto (old deve comparire una volta sola)")
        String editFile(String path, String old, String replacement) {
            System.out.println("  [editFile] " + path);
            if (limit() != null) return limit();
            return safe(() -> {
                String c = Files.readString(p(path));
                if (c.split(java.util.regex.Pattern.quote(old), -1).length != 2) return "errore: 'old' assente o non unico";
                Files.writeString(p(path), c.replace(old, replacement));
                return "ok";
            });
        }

        @Tool(description = "Esegue un comando shell nella root (es. mvn -q compile) e restituisce exit code e output")
        String runCommand(String command) {
            System.out.println("  [runCommand] " + command);
            if (limit() != null) return limit();
            if (confirm) {
                System.out.print("  Eseguire? [s/N] ");
                if (!in.hasNextLine() || !in.nextLine().trim().toLowerCase().startsWith("s")) return "comando rifiutato dall'utente";
            }
            return safe(() -> {
                Process pr = new ProcessBuilder("bash", "-c", command).directory(root.toFile())
                    .redirectErrorStream(true).start();
                String out = new String(pr.getInputStream().readAllBytes());
                if (!pr.waitFor(5, TimeUnit.MINUTES)) pr.destroyForcibly();
                return "exit=" + pr.exitValue() + "\n" + (out.length() > 50000 ? out.substring(out.length() - 50000) : out);
            });
        }

        @Tool(description = "Cerca un testo (regex Java, case-insensitive) nei file di testo sotto una directory; restituisce 'file:riga: testo' con 2 righe di contesto ('file-riga- testo')")
        String searchInFiles(@ToolParam(description = "espressione regolare") String pattern,
                             @ToolParam(description = "directory relativa, '.' per la root") String dir,
                             @ToolParam(description = "glob sul nome file, es. '*.java'; vuoto per tutti") String glob) {
            System.out.println("  [searchInFiles] " + pattern + " in " + dir + " " + glob);
            if (limit() != null) return limit();
            return safe(() -> {
                var re = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
                var gm = FileSystems.getDefault().getPathMatcher("glob:" + (glob == null || glob.isBlank() ? "*" : glob));
                var out = new java.util.ArrayList<String>();
                int n = 0;
                try (Stream<Path> s = Files.walk(p(dir))) {
                    for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                        String rel = root.relativize(f).toString();
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
            System.out.println("  [gitDiff] " + path);
            if (limit() != null) return limit();
            return safe(() -> {
                var cmd = new java.util.ArrayList<>(java.util.List.of("git", "diff", "HEAD"));
                if (path != null && !path.isBlank()) { cmd.add("--"); cmd.add(root.relativize(p(path)).toString()); }
                Process pr = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();
                String out = new String(pr.getInputStream().readAllBytes());
                pr.waitFor();
                return out.isBlank() ? "nessuna modifica" : out.length() > 50000 ? out.substring(0, 50000) + "\n(troncato)" : out;
            });
        }
    }
}
