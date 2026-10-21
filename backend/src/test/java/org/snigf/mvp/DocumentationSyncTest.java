// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.support.PostgisDatabase.RepositoryPaths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documentation rule (docs/technical/09-documentation-rules.md): what the repository
 * defines, the technical manual must describe. Fails naming each missing entry, so a
 * change cannot land without its documentation.
 */
class DocumentationSyncTest {

    private static final Path ROOT = RepositoryPaths.file("compose.yml").getParent();
    private static final Path MANUAL = ROOT.resolve("docs/technical");

    @Test
    void everySettingIsInTheConfigurationReference() {
        List<String> settings = matches(read(".env.example"), "(?m)^(SNIGF_[A-Z0-9_]+)=");
        assertThat(settings).isNotEmpty();
        assertDocumented(settings, manual("05-configuration-reference.md"), "setting");
    }

    @Test
    void everySecretIsInTheConfigurationReference() {
        String compose = read("compose.yml");
        String block = compose.substring(compose.indexOf("\nsecrets:"), compose.indexOf("\nvolumes:"));
        List<String> secrets = matches(block, "(?m)^  ([a-z0-9_]+):\\s+\\{file:");
        assertThat(secrets).isNotEmpty();
        assertDocumented(secrets, manual("05-configuration-reference.md"), "secret");
    }

    @Test
    void everyComposeServiceIsInTheSystemOverview() {
        String compose = read("compose.yml");
        String block = compose.substring(compose.indexOf("\nservices:"), compose.indexOf("\nnetworks:"));
        List<String> services = matches(block, "(?m)^  ([a-z][a-z0-9-]*):\\s*$");
        assertThat(services).contains("app", "db", "openbao");
        assertDocumented(services.stream().map(s -> "`" + s + "`").toList(), manual("01-system-overview.md"), "service");
    }

    @Test
    void everyMigrationIsInTheDataModel() {
        List<String> migrations = list("backend/src/main/resources/db/migration", "V\\d+__.+\\.sql");
        assertThat(migrations).isNotEmpty();
        assertDocumented(migrations, manual("04-data-model.md"), "migration");
    }

    @Test
    void everyModuleIsInTheArchitectureChapter() {
        List<String> modules = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT.resolve("backend/src/main/java/org/snigf/mvp"))) {
            files.filter(p -> p.getFileName().toString().equals("package-info.java"))
                    .filter(p -> read(p).contains("@ApplicationModule"))
                    .forEach(p -> modules.add("org.snigf.mvp." + p.getParent().getFileName()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(modules).contains("org.snigf.mvp.audit");
        assertDocumented(modules, manual("02-backend-architecture.md"), "module");
    }

    @Test
    void everyHealthContributorIsInTheArchitectureChapter() {
        List<String> contributors = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT.resolve("backend/src/main/java"))) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .map(DocumentationSyncTest::read)
                    .filter(source -> source.contains("extends AbstractHealthIndicator"))
                    .forEach(source -> contributors.addAll(matches(source, "@Component\\(\"([A-Za-z]+)\"\\)")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(contributors).contains("keys", "auditChain");
        assertDocumented(contributors.stream().map(c -> "`" + c + "`").toList(),
                manual("02-backend-architecture.md"), "health contributor");
    }

    @Test
    void everyAuditActionIsInTheDataModel() {
        List<String> actions = Stream.of(AuditAction.values()).map(a -> "`" + a.name() + "`").toList();
        assertDocumented(actions, manual("04-data-model.md"), "audit action");
    }

    @Test
    void everyAdrIsIndexed() {
        List<String> adrs = list("docs/adr", "\\d{3}-.+\\.md");
        assertThat(adrs).isNotEmpty();
        assertDocumented(adrs, read("docs/adr/README.md"), "ADR");
    }

    @Test
    void everyGuideIsIndexedAndReachableFromTheManual() {
        List<String> guides = list("docs/guides", "(?!README).+\\.md");
        assertThat(guides).isNotEmpty();
        assertDocumented(guides, read("docs/guides/README.md"), "guide (guides index)");
        StringBuilder manual = new StringBuilder();
        for (String chapter : list("docs/technical", ".+\\.md")) {
            manual.append(manual(chapter));
        }
        assertDocumented(guides, manual.toString(), "guide (technical manual)");
    }

    @Test
    void everyChapterIsInTheManualIndex() {
        List<String> chapters = list("docs/technical", "\\d{2}-.+\\.md");
        assertThat(chapters).hasSizeGreaterThanOrEqualTo(9);
        assertDocumented(chapters, manual("README.md"), "chapter");
    }

    private static void assertDocumented(List<String> names, String document, String kind) {
        List<String> missing = names.stream().filter(n -> !document.contains(n)).toList();
        assertThat(missing).as("%s(s) missing from the documentation (docs/technical/09-documentation-rules.md)", kind)
                .isEmpty();
    }

    private static List<String> matches(String text, String regex) {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found.stream().distinct().toList();
    }

    private static List<String> list(String directory, String nameRegex) {
        try (Stream<Path> files = Files.list(ROOT.resolve(directory))) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.matches(nameRegex)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String manual(String chapter) {
        return read(MANUAL.resolve(chapter));
    }

    private static String read(String relative) {
        return read(ROOT.resolve(relative));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
