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
    static final int MAX_ITERAZIONI = 40, MAX_MESSAGGI = 30;

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
                    + "fallisce correggi e riprova. Rispondi in modo breve alla fine.")
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
                try (Stream<Path> s = Files.walk(p(dir), 6)) {
                    return s.filter(Files::isRegularFile)
                        .map(x -> root.relativize(x).toString())
                        .filter(x -> !x.startsWith(".git") && !x.startsWith("target"))
                        .limit(300).collect(Collectors.joining("\n", "", "\n(fine elenco; se vuoto non ci sono file: crea pure i file)"));
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
                return "exit=" + pr.exitValue() + "\n" + (out.length() > 8000 ? out.substring(out.length() - 8000) : out);
            });
        }

        @Tool(description = "Cerca un testo (regex Java) nei file di testo sotto una directory; restituisce righe 'file:riga: testo'")
        String searchInFiles(@ToolParam(description = "espressione regolare") String pattern,
                             @ToolParam(description = "directory relativa, '.' per la root") String dir) {
            System.out.println("  [searchInFiles] " + pattern + " in " + dir);
            if (limit() != null) return limit();
            return safe(() -> {
                var re = java.util.regex.Pattern.compile(pattern);
                var out = new java.util.ArrayList<String>();
                try (Stream<Path> s = Files.walk(p(dir))) {
                    for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                        String rel = root.relativize(f).toString();
                        if (rel.startsWith(".git") || rel.startsWith("target")) continue;
                        java.util.List<String> lines;
                        try { lines = Files.readAllLines(f); } catch (IOException e) { continue; } // binario
                        for (int i = 0; i < lines.size() && out.size() < 100; i++)
                            if (re.matcher(lines.get(i)).find()) out.add(rel + ":" + (i + 1) + ": " + lines.get(i).strip());
                    }
                }
                return out.isEmpty() ? "nessun risultato" : String.join("\n", out) + (out.size() >= 100 ? "\n(troncato a 100 risultati)" : "");
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
                return out.isBlank() ? "nessuna modifica" : out.length() > 8000 ? out.substring(0, 8000) + "\n(troncato)" : out;
            });
        }
    }
}
