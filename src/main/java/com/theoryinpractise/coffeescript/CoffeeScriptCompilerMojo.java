package com.theoryinpractise.coffeescript;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copyright 2011 Mark Derricutt.
 * <p/>
 * Contributing authors:
 * Daniel Bower
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * <p/>
 * <p/>
 * Compile CoffeeScript with Maven
 *
 * @goal coffee
 * @phase compile
 */
public class CoffeeScriptCompilerMojo extends AbstractMojo {

    /**
     * Default location of .coffee source files.
     *
     * @parameter expression="${basedir}/src/main/coffee"
     * @required
     */
    private File coffeeDir;

    /**
     * Location of the output files from the Coffee Compiler.  Defaults to ${build.directory}/coffee
     *
     * @parameter expression="${project.build.directory}/coffee"
     * @required
     */
    private File coffeeOutputDirectory;

    /**
     * Should we compile as bare?  A compiler option for the Coffee Compiler.
     *
     * @parameter default-value="false"
     */
    private Boolean bare;
    
    /**
     * Should we skip this compile?
     * 
     * @parameter default=value="false"
     */
    private Boolean skip;

    /**
     * What version of Coffee-Script should we compile with?
     *
     * @parameter default-value="1.3.3"
     */
    private String version;

    /**
     * Should the files be compiled individually or as a whole.
     * <p/>
     * This can help when trying to diagnose a compilation error
     *
     * @parameter default-value="false"
     */
    private Boolean compileIndividualFiles;

    /**
     * JoinSet definitions to join groups of coffee files into a single .js file
     * Individual Joinsets contain an id element to name the file that will be output and a maven
     * FileSet to define what files are included.
     *
     * @parameter
     */
    private List<JoinSet> coffeeJoinSets;

    /**
     * This allows you to compile individual files and JoinSets at the same time.
     *
     * @parameter
     */
    private List<JoinSet> individualJoinSets;

    private static final Pattern importStatementPattern = Pattern.compile("#import\\s+\\S+\\.coffee");

    public static void main(String[] args) throws IOException {
        String fileCode = Files.toString(new File("test.coffee"), Charsets.UTF_8);
        new File("test.coffee");
        Matcher importMatcher = importStatementPattern.matcher(fileCode);
        List<String> matches = Lists.newArrayList();
        while(importMatcher.find()){
            matches.add(importMatcher.group());
        }
        System.out.println(matches);
    }

    @VisibleForTesting
    List<String> acceptableVersions = ImmutableList.of("1.2.0", "1.3.1", "1.3.3");

    private String insertImports(File file, String originalCode, List<String> imports) throws Exception {
        boolean topLevel = file.getParent() == null;
        String dir = topLevel ? file.getAbsolutePath() : file.getParent();
        for(String imp : imports) {
            String template = topLevel ? "%s" : "%s/%s";
            String fileToImport = String.format(template, dir, imp.split("\\s+")[1]);
            getLog().info(String.format("Inserting import: %s into File: %s", fileToImport, file));
            try {
                String replacementCode = Files.toString(new File(fileToImport), Charsets.UTF_8) + "\n";
                originalCode = originalCode.replace(imp, replacementCode);
            } catch (Exception e) {
                throw new FileNotFoundException(String.format("Trouble reading file: %s", fileToImport));
            }
        }
        return originalCode;
    }

    public void execute() throws MojoExecutionException {

        if (!acceptableVersions.contains(version)) {

            String error = String.format("Unsupported version of coffee-script specified (%s) - supported versions: %s",
                                                version,
                                                Joiner.on(", ").join(acceptableVersions));

            throw new MojoExecutionException(error);
        }

        getLog().info(String.format("coffee-maven-plugin using coffee script version %s", version));
        if(skip == null){
        	skip = false;
        }
        CoffeeScriptCompiler coffeeScriptCompiler = new CoffeeScriptCompiler(version, bare, skip);
        
        if(!skip){
	        try {
	            if (compileIndividualFiles) {
	                getLog().info("Starting individual compilations of files");
	
	                for (JoinSet joinSet : findJoinSets()) {
	                    StringBuilder compiled = new StringBuilder();
	                    for (File file : joinSet.getFiles()) {
                            String fileCode = Files.toString(file, Charsets.UTF_8);
                            Matcher importMatcher = importStatementPattern.matcher(fileCode);
                            if(importMatcher.find()) {
                                List<String> imports = Lists.newArrayList();
                                imports.add(importMatcher.group());
                                while(importMatcher.find()) {
                                    imports.add(importMatcher.group());
                                }
                                fileCode = insertImports(file, fileCode, imports);
                            }
	                        getLog().info("Compiling File " + file.getName() + " in JoinSet:" + joinSet.getId());
	                        compiled.append(coffeeScriptCompiler.compile(fileCode))
	                                .append("\n");
	                    }
	                    write(joinSet.getCoffeeOutputDirectory(), joinSet.getId(), compiled.toString());
	                }
	            } else {
	                for (JoinSet joinSet : findJoinSets()) {
	                    getLog().info("Compiling JoinSet: " + joinSet.getId() + " with files:  " + joinSet.getFileNames());
	
	                    String compiled = coffeeScriptCompiler.compile(joinSet.getConcatenatedStringOfFiles());
	
	                    write(joinSet.getCoffeeOutputDirectory(), joinSet.getId(), compiled);
	                }
	            }
	
	        } catch (Exception e) {
	            throw new MojoExecutionException(e.getMessage(), e);
	        }
        }
        else{
        	getLog().info("Skipping Execution of Coffeescript compile");
        }
    }

    private List<JoinSet> findJoinSets() throws IOException {
        boolean hasJoinSets = coffeeJoinSets != null && !coffeeJoinSets.isEmpty();
        boolean hasIndividualJoinSets = individualJoinSets != null && !individualJoinSets.isEmpty();

        List<JoinSet> resultList = Lists.newArrayList();
        if (hasJoinSets) {
            resultList.addAll(coffeeJoinSets);
            if(hasIndividualJoinSets) {
                for(JoinSet joinSet : individualJoinSets) {
                    resultList.addAll(getIndividualJionSets(joinSet.getFiles()));
                }
            }
        } else {
            // Generate a joinset for each .coffee file
            return getIndividualJionSets(findCoffeeFilesInDirectory(coffeeDir));
        }
        return resultList;
    }

    private List<JoinSet> getIndividualJionSets(List<File> files) throws IOException {
        return Lists.transform(files, new Function<File, JoinSet>() {
            public JoinSet apply(@Nullable File file) {
                return new StaticJoinSet(file);
            }
        });
    }

    private static class StaticJoinSet extends JoinSet {
        private File file;

        private StaticJoinSet(File file) {
            this.file = file;
            String name = file.getPath().substring(file.getParent().length() + 1);
            setId(name.substring(0, name.lastIndexOf(".")));
        }

        @Override
        public List<File> getFiles() throws IOException {
            return ImmutableList.of(file);
        }
    }


    private List<File> findCoffeeFilesInDirectory(File coffeeDir) {

        List<File> coffeeFiles = Lists.newArrayList();

        File[] files = coffeeDir.listFiles();

        for (File file : files) {

            if (file.isDirectory()) {
                coffeeFiles.addAll(findCoffeeFilesInDirectory(file));
            } else {
                if (file.getPath().endsWith(".coffee")) {
                    coffeeFiles.add(file);
                }
            }
        }

        return coffeeFiles;
    }

    private void write(final File joinSetOutputDirectory, final String fileName, final String contents) throws IOException {
        //Create the new Javascript file path
        File outputDirectory = coffeeOutputDirectory;
        if (joinSetOutputDirectory != null) {
            outputDirectory = joinSetOutputDirectory;
        }
        File jsFile = new File(outputDirectory, fileName + ".js");
        if (!jsFile.getParentFile().exists()) {
            jsFile.getParentFile().mkdirs();
        }

        Files.write(contents, jsFile, Charsets.UTF_8);
    }

}
