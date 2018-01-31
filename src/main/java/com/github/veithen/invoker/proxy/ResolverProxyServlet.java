/*-
 * #%L
 * Resolver Proxy Maven Plugin
 * %%
 * Copyright (C) 2018 Andreas Veithen
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.github.veithen.invoker.proxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.DatatypeConverter;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.shared.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.artifact.resolve.ArtifactResolverException;
import org.codehaus.plexus.util.IOUtil;

@SuppressWarnings("serial")
final class ResolverProxyServlet extends HttpServlet {
    private final Log log;
    private final ArtifactResolver resolver;
    private final MavenSession session;

    ResolverProxyServlet(Log log, ArtifactResolver resolver, MavenSession session) {
        this.log = log;
        this.resolver = resolver;
        this.session = session;
    }

    private DefaultArtifactCoordinate parsePath(String path) {
        int fileSlash = path.lastIndexOf('/');
        if (fileSlash == -1) {
            return null;
        }
        int versionSlash = path.lastIndexOf('/', fileSlash-1);
        if (versionSlash == -1) {
            return null;
        }
        int artifactSlash = path.lastIndexOf('/', versionSlash-1);
        if (artifactSlash == -1) {
            return null;
        }
        String groupId = path.substring(0, artifactSlash).replace('/', '.');
        String artifactId = path.substring(artifactSlash+1, versionSlash);
        String version = path.substring(versionSlash+1, fileSlash);
        String file = path.substring(fileSlash+1);
        if (!file.startsWith(artifactId + "-" + version)) {
            return null;
        }
        String remainder = file.substring(artifactId.length() + version.length() + 1); // Either ".<type>" or "-<classifier>.<type>"
        if (remainder.length() == 0) {
            return null;
        }
        String classifier;
        String extension;
        if (remainder.charAt(0) == '-') {
            int dot = remainder.indexOf('.');
            if (dot == -1) {
                return null;
            }
            classifier = remainder.substring(1, dot);
            extension = remainder.substring(dot+1);
        } else if (remainder.charAt(0) == '.') {
            classifier = null;
            extension = remainder.substring(1);
        } else {
            return null;
        }
        DefaultArtifactCoordinate artifact = new DefaultArtifactCoordinate();
        artifact.setGroupId(groupId);
        artifact.setArtifactId(artifactId);
        artifact.setVersion(version);
        artifact.setClassifier(classifier);
        artifact.setExtension(extension);
        return artifact;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String path = request.getPathInfo();
        DefaultArtifactCoordinate artifact = null;
        if (path != null && path.startsWith("/")) {
            artifact = parsePath(path.substring(1));
        }
        if (artifact == null) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Returning 404 for %s", path));
            }
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        
        // Handle checksum files in a special way. ArtifactResolver would be able to resolve them for artifacts
        // downloaded from a remote repository, but for artifacts from the reactor it will trigger an error. It
        // may also do unnecessary attempts to download them from remote repositories.
        String extension = artifact.getExtension();
        String checksumType = null;
        int idx = extension.lastIndexOf('.');
        if (idx != -1) {
            String suffix = extension.substring(idx+1);
            if (suffix.equals("md5") || suffix.equals("sha1")) {
                artifact.setExtension(extension.substring(0, idx));
                checksumType = suffix;
            }
        }
        
        File file;
        try {
            file = resolver.resolveArtifact(session.getProjectBuildingRequest(), artifact).getArtifact().getFile();
        } catch (ArtifactResolverException ex) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("%s (%s) couldn't be resolved", path, artifact), ex);
            }
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        
        if (checksumType != null) {
            File checksumFile = new File(file.getParent(), String.format("%s.%s", file.getName(), checksumType));
            if (checksumFile.exists()) {
                // Just continue and send the existing checksum file.
                file = checksumFile;
            } else {
                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance(checksumType);
                } catch (NoSuchAlgorithmException ex) {
                    log.error(ex);
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    return;
                }
                byte[] buffer = new byte[4096];
                try (FileInputStream in = new FileInputStream(file)) {
                    int c;
                    while ((c = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, c);
                    }
                }
                String checksum = DatatypeConverter.printHexBinary(digest.digest());
                if (log.isDebugEnabled()) {
                    log.debug(String.format("%s served by computing checksum of %s (%s): %s", path, file, artifact, checksum));
                }
                response.getWriter().write(checksum);
                return;
            }
        }
        
        if (log.isDebugEnabled()) {
            log.debug(String.format("%s (%s, checksumType=%s) resolved to %s", path, artifact, checksumType, file));
        }
        try (FileInputStream in = new FileInputStream(file)) {
            IOUtil.copy(in, response.getOutputStream());
        }
    }
}
