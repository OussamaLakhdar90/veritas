package ca.bnc.qe.veritas;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Single jar, two entrypoints.
 * <ul>
 *   <li>No args, or first arg {@code serve} → web mode (REST + dashboard).</li>
 *   <li>First arg is a subcommand (e.g. {@code echo}, {@code validate-contract}) → CLI mode
 *       ({@link WebApplicationType#NONE}); a {@code CliRunner} executes picocli and the process exits
 *       with the command's exit code.</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class VeritasApplication {

    public static void main(String[] args) {
        boolean cliMode = args.length > 0 && !"serve".equals(args[0]);
        if (cliMode) {
            // System properties override application.yml, so CLI runs stay quiet (clean command output).
            System.setProperty("spring.main.banner-mode", "off");
            System.setProperty("logging.level.root", "WARN");
            System.setProperty("logging.level.ca.bnc.qe.veritas", "WARN");
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(VeritasApplication.class)
                    .web(WebApplicationType.NONE)
                    .properties("veritas.cli=true")
                    .run(args);
            System.exit(SpringApplication.exit(ctx));
        } else {
            String[] webArgs = (args.length > 0 && "serve".equals(args[0]))
                    ? Arrays.copyOfRange(args, 1, args.length)
                    : args;
            new SpringApplicationBuilder(VeritasApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .run(webArgs);
        }
    }
}
