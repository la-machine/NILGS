// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.maps.infrastructure.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snigf.mvp.maps.application.MapProperties;
import org.snigf.mvp.maps.domain.BackgroundKind;
import org.snigf.mvp.maps.application.MapDataStore;
import org.snigf.mvp.maps.domain.MapDataset;
import org.snigf.mvp.maps.domain.MapDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The map data directory (ADR-029): {@code <kind>/manifest.json}, {@code <kind>/<file>.pmtiles},
 * the detail archives a manifest lists (sharper imagery over the base), and the geographic
 * label glyphs under {@code geographic/fonts/}. Read-only in the container.
 * Every path is resolved inside the directory and checked to stay there; a manifest larger
 * than 64 KiB or not matching the rules makes its view unavailable, with the reason logged.
 */
@Component
public class FileMapDataStore implements MapDataStore {

    private static final Logger log = LoggerFactory.getLogger(FileMapDataStore.class);
    private static final long MAX_MANIFEST_BYTES = 64 * 1024;

    private final Path root;
    private final JsonMapper json = JsonMapper.builder().build();

    public FileMapDataStore(MapProperties properties) {
        this.root = properties.dataDirectory().toAbsolutePath().normalize();
    }

    @Override
    public Installed installed(BackgroundKind kind) {
        Path manifest = inside(root.resolve(kind.directory()).resolve("manifest.json"));
        if (manifest == null || !Files.isRegularFile(manifest)) {
            return Installed.unavailable("NOT_INSTALLED", List.of());
        }
        try {
            if (Files.size(manifest) > MAX_MANIFEST_BYTES) {
                return invalid(kind, List.of("MANIFEST_TOO_LARGE"));
            }
            JsonNode m = json.readTree(Files.readAllBytes(manifest));
            double[] bounds = new double[m.path("bounds").size()];
            for (int i = 0; i < bounds.length; i++) {
                bounds[i] = m.path("bounds").get(i).asDouble();
            }
            String file = text(m, "file");
            List<String> problems = MapDataset.problems(kind, text(m, "format"), text(m, "name"), file, text(m, "tileType"),
                    text(m, "attribution"), text(m, "license"), text(m, "source"), text(m, "date"), bounds,
                    m.path("minzoom").asInt(-1), m.path("maxzoom").asInt(-1));
            if (!problems.isEmpty()) {
                return invalid(kind, problems);
            }
            Path archive = inside(root.resolve(kind.directory()).resolve(file));
            if (archive == null || !Files.isRegularFile(archive)) {
                return Installed.unavailable("FILE_MISSING", List.of());
            }
            List<MapDetail> details = details(m.path("details"));
            List<String> detailProblems = new ArrayList<>();
            if (details.size() > MapDetail.MAX_DETAILS) {
                detailProblems.add("TOO_MANY_DETAILS");
            }
            Set<String> files = new HashSet<>();
            for (MapDetail detail : details) {
                detailProblems.addAll(MapDetail.problems(detail, file));
                if (!files.add(String.valueOf(detail.file()))) {
                    detailProblems.add("DETAIL_FILE_DUPLICATE");
                }
            }
            if (!detailProblems.isEmpty()) {
                return invalid(kind, detailProblems.stream().distinct().toList());
            }
            for (MapDetail detail : details) {
                Path detailArchive = inside(root.resolve(kind.directory()).resolve(detail.file()));
                if (detailArchive == null || !Files.isRegularFile(detailArchive)) {
                    return Installed.unavailable("FILE_MISSING", List.of());
                }
            }
            return Installed.usable(new MapDataset(kind, text(m, "name"), file, text(m, "tileType"),
                    text(m, "attribution"), text(m, "license"), text(m, "source"), text(m, "date"), bounds,
                    m.path("minzoom").asInt(), m.path("maxzoom").asInt(), m.path("synthetic").asBoolean(false), details));
        } catch (IOException | RuntimeException e) {
            return invalid(kind, List.of("MANIFEST_UNREADABLE"));
        }
    }

    @Override
    public Optional<Path> archive(BackgroundKind kind) {
        Installed installed = installed(kind);
        if (!installed.available()) {
            return Optional.empty();
        }
        return Optional.ofNullable(inside(root.resolve(kind.directory()).resolve(installed.dataset().file())));
    }

    @Override
    public Optional<Path> archive(BackgroundKind kind, String file) {
        Installed installed = installed(kind);
        if (!installed.available()) {
            return Optional.empty();
        }
        MapDataset dataset = installed.dataset();
        boolean listed = dataset.file().equals(file) || dataset.details().stream().anyMatch(d -> d.file().equals(file));
        return listed ? Optional.ofNullable(inside(root.resolve(kind.directory()).resolve(file))) : Optional.empty();
    }

    @Override
    public Optional<Path> glyphs(String fontstack, String range) {
        if (!installed(BackgroundKind.GEOGRAPHIC).available()) {
            return Optional.empty();
        }
        Path file = inside(root.resolve("geographic").resolve("fonts").resolve(fontstack).resolve(range + ".pbf"));
        return file != null && Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /** The path if it stays inside the data directory once normalized; null otherwise. */
    private Path inside(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        return normalized.startsWith(root) ? normalized : null;
    }

    private Installed invalid(BackgroundKind kind, List<String> problems) {
        log.warn("Map background {} unavailable: manifest problems {}", kind, problems);
        return Installed.unavailable("MANIFEST_INVALID", problems);
    }

    /** The manifest's detail entries, as written; their checks follow. Absent: none. */
    private static List<MapDetail> details(JsonNode array) {
        if (array.isMissingNode() || array.isNull()) {
            return List.of();
        }
        List<MapDetail> details = new ArrayList<>();
        for (JsonNode d : array) {
            double[] bounds = new double[d.path("bounds").size()];
            for (int i = 0; i < bounds.length; i++) {
                bounds[i] = d.path("bounds").get(i).asDouble();
            }
            details.add(new MapDetail(text(d, "name"), text(d, "file"), text(d, "attribution"), text(d, "license"),
                    text(d, "source"), text(d, "date"), bounds, d.path("minzoom").asInt(-1), d.path("maxzoom").asInt(-1)));
        }
        return details;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }
}
