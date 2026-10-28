// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.maps.infrastructure.files;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snigf.mvp.maps.application.MapCatalogue;
import org.snigf.mvp.maps.application.MapProperties;
import org.snigf.mvp.maps.domain.BackgroundKind;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-029: a view is offered only with a valid manifest and its archive; otherwise it says
 * why. Nothing outside the map data directory is ever reachable.
 */
class FileMapDataStoreTest {

    @TempDir
    Path root;

    static String manifest(String kind, String tileType, String file) {
        return """
                {"format":"snigf-map-manifest/1","kind":"%s","name":"Test %s","file":"%s","tileType":"%s",
                 "attribution":"Synthetic test data","license":"none (synthetic)","source":"generated","date":"2026-10-22",
                 "bounds":[8.0,1.5,16.5,13.5],"minzoom":0,"maxzoom":14,"synthetic":true}""".formatted(kind, kind, file, tileType);
    }

    private FileMapDataStore store() {
        return new FileMapDataStore(new MapProperties(root, "none"));
    }

    @Test
    void nothingInstalledMeansOnlyTheTechnicalView() {
        var backgrounds = new MapCatalogue(store()).backgrounds();
        assertThat(backgrounds).extracting(b -> b.kind() + ":" + b.available() + ":" + b.unavailableReason())
                .containsExactly("TECHNICAL:true:null", "GEOGRAPHIC:false:NOT_INSTALLED", "SATELLITE:false:NOT_INSTALLED");
    }

    @Test
    void aValidManifestAndArchiveMakeTheViewAvailable() throws Exception {
        Files.createDirectories(root.resolve("satellite"));
        Files.writeString(root.resolve("satellite/manifest.json"), manifest("satellite", "raster", "tiles.pmtiles"));
        assertThat(store().installed(BackgroundKind.SATELLITE).unavailableReason()).isEqualTo("FILE_MISSING");

        Files.write(root.resolve("satellite/tiles.pmtiles"), new byte[] {'P', 'M', 'T', 'i', 'l', 'e', 's', 3});
        var installed = store().installed(BackgroundKind.SATELLITE);
        assertThat(installed.available()).isTrue();
        assertThat(installed.dataset().synthetic()).isTrue();
        assertThat(store().archive(BackgroundKind.SATELLITE)).contains(root.resolve("satellite/tiles.pmtiles").toAbsolutePath().normalize());
    }

    @Test
    void anInvalidManifestIsNeverOffered() throws Exception {
        Files.createDirectories(root.resolve("geographic"));
        Files.createDirectories(root.resolve("satellite"));
        Files.writeString(root.resolve("geographic/manifest.json"), manifest("geographic", "raster", "tiles.pmtiles"));
        Files.writeString(root.resolve("satellite/manifest.json"), manifest("satellite", "raster", "../../secrets.pmtiles"));
        var geographic = store().installed(BackgroundKind.GEOGRAPHIC);
        assertThat(geographic.unavailableReason()).isEqualTo("MANIFEST_INVALID");
        assertThat(geographic.problems()).contains("TILE_TYPE_MISMATCH");
        assertThat(store().installed(BackgroundKind.SATELLITE).problems()).contains("FILE_NAME_INVALID");

        Files.writeString(root.resolve("satellite/manifest.json"), "{ not json");
        assertThat(store().installed(BackgroundKind.SATELLITE).problems()).containsExactly("MANIFEST_UNREADABLE");
        Files.writeString(root.resolve("satellite/manifest.json"), "x".repeat(70_000));
        assertThat(store().installed(BackgroundKind.SATELLITE).problems()).containsExactly("MANIFEST_TOO_LARGE");
    }

    static String withDetails(String details) {
        return manifest("satellite", "raster", "tiles.pmtiles").replace("\"synthetic\":true}", "\"synthetic\":false,\"details\":" + details + "}");
    }

    static String detail(String file) {
        return """
                {"name":"Aerial survey","file":"%s","attribution":"(c) Survey","license":"CC-BY 4.0","source":"OpenAerialMap",
                 "date":"2026-05-10","bounds":[9.72,3.97,9.77,4.01],"minzoom":14,"maxzoom":20}""".formatted(file);
    }

    @Test
    void detailArchivesAreOfferedAndServedOnlyAsTheManifestListsThem() throws Exception {
        Files.createDirectories(root.resolve("satellite"));
        Files.write(root.resolve("satellite/tiles.pmtiles"), new byte[] {1});
        Files.writeString(root.resolve("satellite/manifest.json"), withDetails("[" + detail("douala-2026.pmtiles") + "]"));
        assertThat(store().installed(BackgroundKind.SATELLITE).unavailableReason()).isEqualTo("FILE_MISSING");

        Files.write(root.resolve("satellite/douala-2026.pmtiles"), new byte[] {2});
        Files.write(root.resolve("satellite/unlisted.pmtiles"), new byte[] {3});
        var installed = store().installed(BackgroundKind.SATELLITE);
        assertThat(installed.available()).isTrue();
        assertThat(installed.dataset().details()).extracting(d -> d.file() + "@" + d.minzoom() + "-" + d.maxzoom())
                .containsExactly("douala-2026.pmtiles@14-20");
        MapCatalogue catalogue = new MapCatalogue(store());
        assertThat(catalogue.archive(BackgroundKind.SATELLITE, "douala-2026.pmtiles")).isPresent();
        assertThat(catalogue.archive(BackgroundKind.SATELLITE, "tiles.pmtiles")).isPresent();
        assertThat(catalogue.archive(BackgroundKind.SATELLITE, "unlisted.pmtiles")).isEmpty();
        assertThat(catalogue.archive(BackgroundKind.SATELLITE, "manifest.json")).isEmpty();
        assertThat(catalogue.archive(BackgroundKind.TECHNICAL, "tiles.pmtiles")).isEmpty();
    }

    @Test
    void anInvalidDetailMakesTheWholeViewUnavailable() throws Exception {
        Files.createDirectories(root.resolve("satellite"));
        Files.write(root.resolve("satellite/tiles.pmtiles"), new byte[] {1});
        Files.writeString(root.resolve("satellite/manifest.json"), withDetails("[" + detail("../outside.pmtiles") + "]"));
        assertThat(store().installed(BackgroundKind.SATELLITE).problems()).contains("DETAIL_FILE_NAME_INVALID");
        Files.writeString(root.resolve("satellite/manifest.json"), withDetails("[" + detail("tiles.pmtiles") + "]"));
        assertThat(store().installed(BackgroundKind.SATELLITE).problems()).contains("DETAIL_FILE_NAME_INVALID");
        Files.writeString(root.resolve("satellite/manifest.json"),
                withDetails("[" + detail("a.pmtiles") + "," + detail("a.pmtiles") + "]"));
        assertThat(store().installed(BackgroundKind.SATELLITE).problems()).contains("DETAIL_FILE_DUPLICATE");
        Files.writeString(root.resolve("satellite/manifest.json"),
                withDetails("[" + detail("a.pmtiles").replace("\"attribution\":\"(c) Survey\",", "") + "]"));
        assertThat(store().installed(BackgroundKind.SATELLITE).problems()).contains("DETAIL_DESCRIPTION_INCOMPLETE");
        Files.writeString(root.resolve("satellite/manifest.json"),
                withDetails("[" + detail("a.pmtiles").replace("\"minzoom\":14", "\"minzoom\":21") + "]"));
        assertThat(store().installed(BackgroundKind.SATELLITE).problems()).contains("DETAIL_ZOOM_INVALID");
    }

    @Test
    void glyphNamesCannotLeaveTheDirectory() throws Exception {
        Files.createDirectories(root.resolve("geographic/fonts/Noto Sans Regular"));
        Files.writeString(root.resolve("geographic/manifest.json"), manifest("geographic", "vector", "tiles.pmtiles"));
        Files.write(root.resolve("geographic/tiles.pmtiles"), new byte[] {1});
        Files.write(root.resolve("geographic/fonts/Noto Sans Regular/0-255.pbf"), new byte[] {2});
        MapCatalogue catalogue = new MapCatalogue(store());
        assertThat(catalogue.glyphs("Noto Sans Regular", "0-255")).isPresent();
        assertThat(catalogue.glyphs("../../satellite", "0-255")).isEmpty();
        assertThat(catalogue.glyphs("Noto Sans Regular", "../manifest")).isEmpty();
        assertThat(catalogue.glyphs("Noto Sans Regular", "256-511")).isEmpty();
    }
}
