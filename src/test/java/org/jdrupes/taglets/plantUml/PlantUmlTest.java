package org.jdrupes.taglets.plantUml;

import com.sun.source.doctree.DocTree;
import jdk.javadoc.doclet.DocletEnvironment;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.util.Elements;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class PlantUmlTest {
    @Test
    void generatesImgTagFromClassComment() throws Exception {
        DocletEnvironment env = mock(DocletEnvironment.class);
        JavaFileManager fileManager = mock(JavaFileManager.class);
        Elements elementsUtils = mock(Elements.class);

        when(env.getJavaFileManager()).thenReturn(fileManager);
        when(env.getElementUtils()).thenReturn(elementsUtils);

        FileObject outputFile = mock(FileObject.class);
        ByteArrayOutputStream mockStream = new ByteArrayOutputStream();
        when(outputFile.openOutputStream()).thenReturn(mockStream);

        when(outputFile.getCharContent(anyBoolean()))
                .thenReturn("");

        when(fileManager.getFileForOutput(any(), any(), any(), any()))
                .thenReturn(outputFile);

        Name packageName = mock(Name.class);
        when(packageName.toString()).thenReturn("com.example");

        PackageElement packageElement = mock(PackageElement.class);
        when(packageElement.getQualifiedName()).thenReturn(packageName);
        when(elementsUtils.getPackageOf(any(Element.class)))
                .thenReturn(packageElement);

        PlantUml plantUml = new PlantUml();
        plantUml.init(env, null);

        DocTree docTree = mock(DocTree.class);
        when(docTree.toString()).thenReturn(
                """
                        @plantUml
                        title Project deployment diagram
                        database "PostgreSQL 15+"
                        node postgres_log_parser #palegreen
                        node "OpenTelemetry collector"
                        postgres_log_parser - "PostgreSQL 15+" : watch changes and parse JSON logs
                        postgres_log_parser -(0- "OpenTelemetry collector": \\u0434\\u0430 sending the logs"""
        );

        System.setProperty("diagram.hidden.by.default", "true");

        assertWithExpandableDiagram(plantUml.toString(List.of(docTree), mock(Element.class)), mockStream);

        System.setProperty("diagram.hidden.by.default", "false");

        assertWithoutExpandableDiagramControl(plantUml.toString(List.of(docTree), mock(Element.class)));
    }

    private static void assertWithoutExpandableDiagramControl(String result) {
        assertAll(
                () -> assertNotNull(result, "Should generate non-null output"),
                () -> assertTrue(result.startsWith("<img src=\""),
                        "Should generate image tag"),
                () -> assertTrue(result.contains(".svg\""),
                        "Should reference SVG file"),
                () -> assertFalse(result.contains("<div style=\"display:none;\"><img src=\""),
                        "Image should remain hidden initially")
        );
    }

    private static void assertWithExpandableDiagram(String result, ByteArrayOutputStream mockStream) {
        assertAll(
                () -> assertNotNull(result, "Should generate non-null output"),
                () -> assertTrue(result.contains("<img src=\""),
                        "Should generate image tag"),
                () -> assertTrue(result.contains(".svg\""),
                        "Should reference SVG file"),
                () -> assertTrue(result.contains("Project deployment diagram</a>"),
                        "Link should show diagram title"),
                () -> assertTrue(result.contains("<div style=\"display:none;\"><img src=\""),
                        "Image should remain hidden initially")
        );

        assertTrue(mockStream.size() > 0, "Should write to output stream");
        String svgContent = mockStream.toString(StandardCharsets.UTF_8);
        assertAll(
                () -> assertTrue(svgContent.contains("Project deployment diagram"),
                        "SVG should contain diagram title"),
                () -> assertTrue(svgContent.contains("PostgreSQL 15+"),
                        "SVG should contain database label"),
                () -> assertTrue(svgContent.contains("postgres_log_parser"),
                        "SVG should contain parser node"),
                () -> assertTrue(svgContent.contains("OpenTelemetry collector"),
                        "SVG should contain collector node"),
                () -> assertTrue(svgContent.contains("&#1076;&#1072; sending the logs"),
                        "SVG should contain Unicode connection label")
        );
    }
}
