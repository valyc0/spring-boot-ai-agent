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

    public static void main(String[] args) {
        SpringApplication.run(Agent.class, args);
    }

    @Bean
    CommandLineRunner cli(ChatClient.Builder builder) {
        return args -> {
            Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
            var chat = builder
                .defaultSystem("Sei un coding agent. Lavori nella directory " + root + ". Usa i tool per esplorare, "
                    + "leggere, creare e modificare file e per compilare (es. 'mvn -q compile'). Se la compilazione "
                    + "fallisce correggi e riprova. Rispondi in modo breve alla fine.")
                .defaultTools(new Tools(root))
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).build())
                .build();
            System.out.println("Agent su " + root + " (exit per uscire)");
            var in = new Scanner(System.in);
            while (true) {
                System.out.print("\n> ");
                if (!in.hasNextLine()) break;
                String q = in.nextLine().trim();
                if (q.equals("exit")) break;
                if (q.isEmpty()) continue;
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

    record Tools(Path root) {

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
            return safe(() -> Files.readString(p(path)));
        }

        @Tool(description = "Crea o sovrascrive un file con il contenuto completo (crea le directory mancanti)")
        String writeFile(@ToolParam(description = "percorso relativo") String path,
                         @ToolParam(description = "contenuto completo") String content) {
            System.out.println("  [writeFile] " + path);
            return safe(() -> {
                Files.createDirectories(p(path).getParent());
                Files.writeString(p(path), content);
                return "ok";
            });
        }

        @Tool(description = "Modifica un file sostituendo un testo esatto (old deve comparire una volta sola)")
        String editFile(String path, String old, String replacement) {
            System.out.println("  [editFile] " + path);
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
            return safe(() -> {
                Process pr = new ProcessBuilder("bash", "-c", command).directory(root.toFile())
                    .redirectErrorStream(true).start();
                String out = new String(pr.getInputStream().readAllBytes());
                if (!pr.waitFor(5, TimeUnit.MINUTES)) pr.destroyForcibly();
                return "exit=" + pr.exitValue() + "\n" + (out.length() > 8000 ? out.substring(out.length() - 8000) : out);
            });
        }
    }
}
