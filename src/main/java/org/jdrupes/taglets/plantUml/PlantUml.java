/*
 * JDrupes MDoclet
 * Copyright (C) 2021 Michael N. Lipp
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with this program; if not, see <http://www.gnu.org/licenses/>.
 */

package org.jdrupes.taglets.plantUml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.lang.model.element.*;
import javax.lang.model.util.SimpleElementVisitor14;
import javax.tools.DocumentationTool;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.ToolProvider;

import com.sun.source.doctree.DocTree;

import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Taglet;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.preproc.Defines;

/**
 * A JDK11 doclet that generates UML diagrams from PlantUML
 * specifications in the comment.
 */
public class PlantUml implements Taglet {

    private static final Logger logger
            = Logger.getLogger(PlantUml.class.getName());
    private final Pattern urlMatcher = Pattern.compile("\\w+://.*");

    private DocletEnvironment env;
    private JavaFileManager fileManager;
    private List<String> plantConfigData;

    public static void main(String[] args) {
        DocumentationTool documentationTool = ToolProvider.getSystemDocumentationTool();
        documentationTool.run(System.in, System.out, System.err, args);
    }

    @Override
    public void init(DocletEnvironment env, Doclet doclet) {
        this.env = env;
        fileManager = env.getJavaFileManager();
    }

    @Override
    public String getName() {
        return "plantUml";
    }

    @Override
    public Set<Location> getAllowedLocations() {
        return Set.of(Taglet.Location.OVERVIEW, Taglet.Location.PACKAGE,
                Taglet.Location.TYPE, Location.METHOD);
    }

    @Override
    public boolean isInlineTag() {
        return true;
    }

    @Override
    public boolean isBlockTag() {
        return true;
    }

    @Override
    public String toString(List<? extends DocTree> tags, Element element) {
        StringBuilder stringBuilder = new StringBuilder();
        for (DocTree tagTree : tags) {
            stringBuilder.append(processTag(tagTree, element));
        }
        return stringBuilder.toString();
    }

    private String processTag(DocTree tree, Element element) {
        String plantUmlSource = tree.toString();
        String[] splitSource = plantUmlSource.split("\\s", 2);
        String content;
        if(element.getKind()==ElementKind.METHOD && (splitSource.length==1 || splitSource[1].isEmpty())){
            String className = env.getElementUtils().getOutermostTypeElement(element).getQualifiedName().toString().replace(".","_");
            String method = element.getSimpleName().toString();
            String fullName= className+"_"+method+".sequence.uml";
            File plantUmlDiaFile = new File(System.getProperty("sequence.uml.basepath", System.getProperty("user.dir")), fullName);
            if(!plantUmlDiaFile.exists()){
                throw new IllegalArgumentException("File not found: "+plantUmlDiaFile.getAbsolutePath());
            }
            try {
                content = Files.readString(plantUmlDiaFile.toPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            if (splitSource.length < 2) {
                logger.log(Level.WARNING, "Invalid %0 tag. Content: %1",
                        new Object[] { getName(), tree.toString() });
                throw new IllegalArgumentException("Invalid " + getName()
                        + " tag: Expected filename and PlantUML source");
            }
            content = splitSource[1].trim();
        }

        String packageName = "";
        String elementType = element.getClass().getName();
        if (elementType.endsWith("Symbol$ClassSymbol")
                || elementType.endsWith("Symbol$PackageSymbol")
                || elementType.endsWith("Symbol$MethodSymbol")) {
            packageName = extractPackageName(element);
        }
        FileObject graphicsFile;
        String fileName = UUID.randomUUID() +".svg";
        try {
            graphicsFile = fileManager.getFileForOutput(
                    DocumentationTool.Location.DOCUMENTATION_OUTPUT, packageName,
                    fileName, null);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Error generating output file for " + packageName + "/"
                            + fileName + ": " + e.getLocalizedMessage());
        }

        // render


        if(urlMatcher.matcher(content).matches()){
            try (InputStream stream = new URI(content).toURL().openStream()){
                if(stream==null){
                    throw new RuntimeException("Resource not found " + content);
                }
                plantUmlSource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            } catch (Exception e) {
                throw new RuntimeException("Resource " + content, e);
            }
        } else {
            if(!content.startsWith("@startuml")){
                plantUmlSource = "@startuml\n" + content + "\n@enduml";
            } else {
                plantUmlSource = content;
            }
        }
        SourceStringReader reader = new SourceStringReader(
                Defines.createEmpty(), plantUmlSource, plantConfig());
        try (OutputStream outputStream = graphicsFile.openOutputStream()){
            reader.outputImage(outputStream, new FileFormatOption(getFileFormat(fileName)));
        } catch (IOException e) {
            throw new RuntimeException(
                    "Error generating UML image " + graphicsFile.getName() + ": "
                            + e.getLocalizedMessage());
        }

        try {
            CharSequence graphicsFileCharContent = graphicsFile.getCharContent(true);
            if(graphicsFileCharContent.length()<15000 && String.valueOf(graphicsFileCharContent).
                    contains("\">Cannot open URL</text><!--SRC=[")){
                throw new RuntimeException("PlantUML diagram generation error 'Cannot open URL'. " +
                        "Please check file "+graphicsFile.toUri().getPath());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return "<img src=\""+ fileName +"\"> ";
    }

    private String extractPackageName(Element element) {
        return element.accept(new SimpleElementVisitor14<>() {

            @Override
            public Name visitPackage(PackageElement e, Object p) {
                return e.getQualifiedName();
            }

            @Override
            public Name visitType(TypeElement e, Object p) {
                return env.getElementUtils().getPackageOf(e).getQualifiedName();
            }

            @Override
            protected Object defaultAction(Element e, Object object) {
                return env.getElementUtils().getPackageOf(e).getQualifiedName();
            }
        }, null).toString();
    }

    private FileFormat getFileFormat(String name) {
        for (FileFormat f : FileFormat.values()) {
            if (name.toLowerCase().endsWith(f.getFileSuffix())) {
                return f;
            }
        }
        String msg = "Unsupported file extension: " + name;
        throw new IllegalArgumentException(msg);
    }

    private List<String> plantConfig() {
        if (plantConfigData != null) {
            return plantConfigData;
        }
        plantConfigData = new ArrayList<>();
        String configFileName
                = System.getProperty(getClass().getName() + ".config");
        if (configFileName == null) {
            return plantConfigData;
        }
        try {
            plantConfigData = Collections.singletonList(
                    new String(Files.readAllBytes(Paths.get(configFileName))));
        } catch (IOException e) {
            throw new RuntimeException(
                    "Error loading PlantUML configuration file "
                            + configFileName + ": " + e.getLocalizedMessage());
        }

        return plantConfigData;
    }
}
