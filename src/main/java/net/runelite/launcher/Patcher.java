/*
 * Copyright (c) 2016-2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.launcher;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import joptsimple.OptionSet;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.launcher.beans.Artifact;
import net.runelite.launcher.beans.Bootstrap;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("ALL")
@Slf4j
public class Patcher {

    private static final File RUNELITE_DIR = new File(System.getProperty("user.home"), ".runelite");
    private static final File REPO_DIR = new File(RUNELITE_DIR, "repository2");
    private static final File PATCH_DIR = new File(RUNELITE_DIR, "patches");
    private static final Pattern VERSION_PATTERN = Pattern.compile("-\\d[^a-zA-z]*");
    private static final HashMap<String, Patch> ARTIFACTS = new HashMap<>();
    private static final HashMap<String, Patch> REPOS = new HashMap<>();
    private static final HashMap<String, Patch> PATCHES = new HashMap<>();
    private static final HashMap<String, Patch> SNAPSHOTS = new HashMap<>();



    static String[] patchBootstrap(LauncherFrame frame, Bootstrap bootstrap, String[] args) throws IOException
    {
        System.clearProperty("runelite.launcher.version");
        //System.setProperty("runelite.launcher.nojvm", "false");

        List<String> options = new ArrayList<String>(Arrays.asList(args));
        if(!options.contains("--clientargs")) {
            options.add("--clientargs");
        }
        if(!options.contains("--no-developer-mode")) {
            options.add("--developer-mode");
        }
        args = (String[])options.toArray(new String[0]);

        // Assertions must be enabled for developer-mode to be enabled
        // setDefaultAssertionStatus(true) enables them for the ReflectionLauncher
        // Adding -ea to the arguments enables them for the JvmLauncher.
        List<String> clientJvmArguments = new ArrayList<String>(Arrays.asList(bootstrap.getClientJvmArguments()));
        clientJvmArguments.add("-ea");
        bootstrap.setClientJvmArguments((String[])clientJvmArguments.toArray(new String[0]));
        List<String> clientJvm9Arguments = new ArrayList<String>(Arrays.asList(bootstrap.getClientJvm9Arguments()));
        clientJvm9Arguments.add("-ea");
        bootstrap.setClientJvm9Arguments((String[])clientJvm9Arguments.toArray(new String[0]));

        PATCH_DIR.mkdirs();

        File[] patches = PATCH_DIR.listFiles();

        if (patches.length == 0)
        {
            return args;
        }

        ArrayList<Artifact> artifacts = new ArrayList<>();
        Artifact[] bootstrapArtifacts = bootstrap.getArtifacts();

        for (Artifact artifact : bootstrapArtifacts)
        {
            ARTIFACTS.put(artifact.getName(), new Patch(artifact.getName(), new URL(artifact.getPath()), artifact.getHash()));
        }

        for (File patch : REPO_DIR.listFiles())
        {
            if (patch.getName().endsWith(".jar") && !patch.isDirectory())
            {
                REPOS.put(patch.getName(), new Patch(patch.getName(), patch.toURI().toURL(), hash(patch)));
            }
        }

        for (File patch : PATCH_DIR.listFiles())
        {
            if (patch.getName().endsWith(".jar") && !patch.isDirectory())
            {
                PATCHES.put(patch.getName(), new Patch(patch.getName(), patch.toURI().toURL(),hash(patch)));
            }
        }

        HashSet<File> jars = new HashSet<>();
        for (File patch : patches)
        {
            if (!patch.isDirectory())
            {
                continue;
            }
            File directory = new File (patch.getPath(), "snapshot");
            directory.mkdirs();

            if (directory.listFiles().length == 0)
            {
                URL url = getUpToDateURL(patch);
                String filename = url.toString().substring(url.toString().lastIndexOf('/') + 1);
                File dest = new File(directory, filename);
                copySource(url, dest, frame);
                patchJar(dest);
                SNAPSHOTS.put(filename, new Patch(filename, dest.toURI().toURL(), hash(dest)));
            }
            else if (directory.listFiles().length == 1)
            {
                File original = directory.listFiles()[0];
                String filename = original.getName().substring(original.getName().lastIndexOf(FileSystems.getDefault().getSeparator()) + 1);
                SNAPSHOTS.put(filename, new Patch(filename, original.toURI().toURL(), hash(original)));
                URL url = getUpToDateURL(original);
                if (!url.getPath().equals(original.toURI().getPath()))
                {
                    if (original.delete())
                    {
                        log.debug("Deleted old artifact {}", original);
                    }
                    else
                    {
                        log.warn("Unable to delete old artifact {}", original);
                    }
                    String latest = url.toString().substring(url.toString().lastIndexOf('/') + 1);
                    File dest = new File(directory, latest);
                    SNAPSHOTS.remove(filename);
                    copySource(url, dest, frame);
                    patchJar(dest);
                    SNAPSHOTS.put(latest, new Patch(latest, dest.toURI().toURL(), hash(dest)));
                }
            }
        }
        for (Patch patch : PATCHES.values())
        {
            if (SNAPSHOTS.containsKey(patch.getName()))
            {
                continue;
            }
            if (ARTIFACTS.containsKey(patch.getName()))
            {
                ARTIFACTS.remove(patch.getName());
            }
            Artifact artifact = buildArtifact(new File(patch.getUrl().getFile()));
            verifyArtifact(frame, artifact);
            artifacts.add(artifact);
        }
        for (Patch snapshot : SNAPSHOTS.values())
        {
            if (ARTIFACTS.containsKey(snapshot.getName()))
            {
                ARTIFACTS.remove(snapshot.getName());
            }
            Artifact artifact = buildArtifact(new File(snapshot.getUrl().getFile()));
            verifyArtifact(frame, artifact);
            artifacts.add(artifact);
        }
        for (Artifact art : bootstrapArtifacts)
        {
            String name = art.getName().substring(art.getName().lastIndexOf('/') + 1);
            if (PATCHES.containsKey(name) || SNAPSHOTS.containsKey(name))
            {
                ARTIFACTS.remove(name);
                continue;
            }
            artifacts.add(art);
        }
        bootstrap.setArtifacts(artifacts.toArray(new Artifact[artifacts.size()]));
        ARTIFACTS.clear();
        REPOS.clear();
        PATCHES.clear();
        SNAPSHOTS.clear();
        return args;
    }

    private static void patchJar(File jar) throws IOException
    {
        if(!jar.getName().endsWith(".jar") || !jar.getParentFile().getName().equals("snapshot"))
        {
            return;
        }
        File directory = jar.getParentFile().getParentFile();
        for (File file : directory.listFiles())
        {
            if (file.getName().equals("snapshot"))
            {
                continue;
            }
            if (file.getName().endsWith(".jar"))
            {
                Map<String, String> env = new HashMap<>();
                env.put("create", "true");
                Enumeration<JarEntry> entries = new JarFile(file).entries();
                URI uriDest = URI.create("jar:" + jar.toURI().toURL().toString());
                java.nio.file.FileSystem dest = FileSystems.newFileSystem(uriDest, env);
                URI uriSrc = URI.create("jar:" + file.toURI().toURL().toString());
                java.nio.file.FileSystem src = FileSystems.newFileSystem(uriSrc, env);
                while (entries.hasMoreElements())
                {
                    JarEntry entry = entries.nextElement();
                    Path destPath = dest.getPath(entry.getName());
                    Path srcPath = src.getPath(entry.getName());
                    if (java.nio.file.Files.exists(destPath) && java.nio.file.Files.isDirectory(destPath))
                    {
                        continue;
                    }
                    java.nio.file.Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING);
                }
                dest.close();
                src.close();
            }
        }
    }

    private static Artifact buildArtifact(File file) throws IOException
    {
        Artifact artifact = new Artifact();
        artifact.setName(file.getName());
        artifact.setPath(file.toURI().toURL().toString());
        artifact.setHash(Files.asByteSource(file).hash(Hashing.sha256()).toString());
        artifact.setSize((int)file.length());
        return artifact;
    }

    private static boolean verifyArtifact(LauncherFrame frame, Artifact artifact) throws IOException
    {
        File repDest = new File(REPO_DIR, artifact.getName());

        String hash;

        try
        {
            hash = hash(repDest);
        }
        catch (FileNotFoundException ex)
        {
            hash = null;
        }

        if (Objects.equals(hash, artifact.getHash()))
        {
            log.debug("Hash for {} up to date", artifact.getName());
            return true;
        }

        copySource(new URL(artifact.getPath()), new File(REPO_DIR, artifact.getName()), frame);
        return Objects.equals(hash(repDest), artifact.getHash());
    }

    private static String hash(File file) throws IOException
    {
        HashFunction sha256 = Hashing.sha256();
        return Files.asByteSource(file).hash(sha256).toString();
    }

    private static void copySource(URL src, File dest, LauncherFrame frame) throws IOException
    {
        if (dest.exists())
        {
            if (dest.delete())
            {
                log.debug("Deleted old artifact {}", dest);
            }
            else
            {
                log.warn("Unable to delete old artifact {}", dest);
            }
        }

        log.debug("Downloading {}", dest.getName());

        URLConnection conn = src.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (InputStream in = conn.getInputStream();
             FileOutputStream fout = new FileOutputStream(dest)) {
            int i;
            int bytes = 0;
            byte[] buffer = new byte[1024 * 1024];
            while ((i = in.read(buffer)) != -1) {
                bytes += i;
                fout.write(buffer, 0, i);
                frame.progress(dest.getName(), bytes, (int)dest.length());
            }
        }
    }

    private static URL getUpToDateURL(File patch) throws MalformedURLException {
        String filename = Patch.removeVersion(patch.getName());

        Patch pFile = null, rFile = null, aFile = null, sFile = null, temp = null;
        for(Patch file : PATCHES.values()) {
            if (file.shortName.equals(filename)) temp = Patch.getNewestPatch(file, temp);
        }
        pFile = temp;
        temp = null;
        for(Patch file : REPOS.values()) {
            if (file.shortName.equals(filename)) temp = Patch.getNewestPatch(file, temp);
        }
        rFile = temp;
        temp = null;
        for(Patch file : ARTIFACTS.values()) {
            if (file.shortName.equals(filename)) temp = Patch.getNewestPatch(file, temp);
        }
        aFile = temp;
        temp = null;
        for(Patch file : SNAPSHOTS.values())
        {
            if (file.shortName.equals(filename)) temp = Patch.getNewestPatch(file, temp);
        }
        sFile = temp;
        if (pFile == null && rFile == null && aFile == null && sFile == null)
            return patch.toURI().toURL();
        if (Patch.getNewestPatch(pFile, sFile) == sFile) {
            if (Patch.getNewestPatch(sFile, rFile) == sFile) {
                if (Patch.getNewestPatch(sFile, aFile) == sFile) return sFile.getUrl();
                else return aFile.getUrl();
            }
            else {
                if (Patch.getNewestPatch(rFile, aFile) == rFile)
                {
                    if( rFile == null) {
                        System.out.println("test1");

                    }
                    if(aFile == null) {
                        System.out.println("test2");
                    }
                    return rFile == null ? aFile.getUrl() : !aFile.getHash().equals(rFile.getHash()) ? aFile.getUrl() : rFile.getUrl();
                }
                else return aFile.getUrl();
            }
        }
        else {
            if (Patch.getNewestPatch(pFile, rFile) == pFile) {
                if (Patch.getNewestPatch(pFile, aFile) == pFile) return pFile.getUrl();
                else return aFile.getUrl();
            }
            else {
                if (Patch.getNewestPatch(rFile, aFile) == rFile) return rFile.getUrl();
                else return aFile.getUrl();
            }
        }
    }

    @Data
    private static class Patch
    {
        private String name;
        private String shortName;
        private String version;
        private URL url;
        private String hash;
        private Patch(String name, URL url, String hash)
        {
            this.name = name;
            this.url = url;
            this.hash = hash;
            Matcher matcher = VERSION_PATTERN.matcher(name);
            if (matcher.find())
            {
                this.version = matcher.group(0).substring(1, matcher.group(0).length()-1);
                this.shortName = name.replaceFirst(this.version, "");
                return;
            }
            this.shortName = name;
            this.version = null;
        }

        private static Patch getNewestPatch(Patch p1, Patch p2)
        {
            if((p1 == null || p1.version == null) && (p2 == null || p2.version == null))
            {
                return p1;
            }
            if(p2 == null || p2.version == null)
            {
                return p1;
            }
            if(p1 == null || p1.version == null)
            {
                return p2;
            }
            String[] thisParts = p1.version.split("\\.");
            String[] thatParts = p2.version.split("\\.");
            int length = Math.max(thisParts.length, thatParts.length);
            for(int i = 0; i < length; i++) {
                int thisPart = i < thisParts.length ?
                        Integer.parseInt(thisParts[i]) : 0;
                int thatPart = i < thatParts.length ?
                        Integer.parseInt(thatParts[i]) : 0;
                if(thisPart < thatPart)
                {
                    return p2;
                }
                if(thisPart > thatPart)
                {
                    return p1;
                }
            }
            return p1;
        }
        private static String removeVersion(String filename)
        {
            Matcher matcher = VERSION_PATTERN.matcher(filename);
            if (matcher.find())
            {
                return filename.replaceFirst(matcher.group(0).substring(1, matcher.group(0).length()-1), "");
            }
            return filename;
        }
    }
}
