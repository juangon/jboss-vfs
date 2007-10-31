/*
* JBoss, Home of Professional Open Source
* Copyright 2006, JBoss Inc., and individual contributors as indicated
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*/
package org.jboss.virtual.plugins.context.vfs;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.regex.Pattern;

import org.jboss.virtual.VFS;
import org.jboss.virtual.VirtualFile;
import org.jboss.virtual.VirtualFileFilter;
import org.jboss.virtual.VisitorAttributes;
import org.jboss.virtual.plugins.context.jar.JarUtils;
import org.jboss.virtual.plugins.vfs.helpers.FilterVirtualFileVisitor;
import org.jboss.virtual.plugins.vfs.helpers.SuffixesExcludeFilter;
import org.jboss.virtual.spi.VirtualFileHandler;

/**
 * Extension of VirtualFile that represents a virtual directory that can be composed of arbitrary files and resources
 * spread throughout the file system or embedded in jar files.
 *
 * @author <a href="bill@jboss.com">Bill Burke</a>
 * @version $Revision: 1.1 $
 */
public class AssembledDirectory extends VirtualFile
{
   private AssembledDirectoryHandler directory;

   public AssembledDirectory(VirtualFileHandler handler)
   {
      super(handler);
      directory = (AssembledDirectoryHandler) handler;
   }

   /**
    * Find the underlying .class file representing this class and create it within this directory, along with
    * its packages.
    *
    * So, if you added com.acme.Customer class, then a directory structure com/acme would be created
    * and an entry in the acme directory would be the .class file.
    *
    * @param clazz
    */
   public void addClass(Class clazz)
   {
      addClass(clazz.getName(), clazz.getClassLoader());
   }

   /**
    * Find the underlying .class file representing this class and create it within this directory, along with
    * its packages.
    *
    * So, if you added com.acme.Customer class, then a directory structure com/acme would be created
    * and an entry in the acme directory would be the .class file.
    *
    * @param className
    */
   public void addClass(String className)
   {
      addClass(className, Thread.currentThread().getContextClassLoader());
   }

   /**
    * Find the underlying .class file representing this class and create it within this directory, along with
    * its packages.
    *
    * So, if you added com.acme.Customer class, then a directory structure com/acme would be created
    * and an entry in the acme directory would be the .class file.
    *
    * @param className
    * @param loader ClassLoader to look for class resource
    */
   public void addClass(String className, ClassLoader loader)
   {
      String resource = className.replace('.', '/') + ".class";
      URL url = loader.getResource(resource);
      if (url == null) throw new RuntimeException("Could not find resource: " + resource);
      AssembledDirectory p = mkdirs(resource);
      p.addResource(resource, loader);
   }

   /**
    * Make any directories for the give path to a file.
    *
    * @param path must be a path to a file as last element in path does not have a directory created
    * @return directory file will live in
    */
   public AssembledDirectory mkdirs(String path)
   {
      String[] pkgs = path.split("/");
      AssembledDirectoryHandler dir = directory;
      for (int i = 0; i < pkgs.length - 1; i++)
      {
         AssembledDirectoryHandler next = (AssembledDirectoryHandler) dir.getChild(pkgs[i]);
         if (next == null)
         {
            try
            {
               next = new AssembledDirectoryHandler((AssembledContext) dir.getVFSContext(), dir, pkgs[i]);
            }
            catch (IOException e)
            {
               throw new RuntimeException(e);
            }
            dir.addChild(next);
         }
         dir = next;
      }
      AssembledDirectory p = (AssembledDirectory) dir.getVirtualFile();
      return p;
   }

   /**
    * Locate the .class resource of baseResource.  From this resource, determine the base of the resource
    * i.e. what jar or classpath directory it lives in.
    *
    * Once the base of the resource is found, scan all files recursively within the base using the include and exclude
    * patterns.  A mirror file structure will be created within this AssembledDirectory. Ths is very useful when you
    * want to create a virtual jar that contains a subset of .class files in your classpath.
    *
    * The include/exclude patterns follow the Ant file pattern matching syntax.  See ant.apache.org for more details.
    *
    * @param baseResource
    * @param includes
    * @param excludes
    */
   public void addResources(Class baseResource, String[] includes, String[] excludes)
   {
      String resource = baseResource.getName().replace('.', '/') + ".class";
      addResources(resource, includes, excludes);
   }

   /**
    * From the baseResource, determine the base of that resource
    * i.e. what jar or classpath directory it lives in.  The Thread.currentThread().getContextClassloader() will be used
    *
    * Once the base of the resource is found, scan all files recursively within the base using the include and exclude
    * patterns.  A mirror file structure will be created within this AssembledDirectory. Ths is very useful when you
    * want to create a virtual jar that contains a subset of .class files in your classpath.
    *
    * The include/exclude patterns follow the Ant file pattern matching syntax.  See ant.apache.org for more details.
    *
    * @param baseResource
    * @param includes
    * @param excludes
    */
   public void addResources(String baseResource, final String[] includes, final String[] excludes)
   {
      addResources(baseResource, includes, excludes, Thread.currentThread().getContextClassLoader());   
   }

   /**
    * From the baseResource, determine the base of that resource
    * i.e. what jar or classpath directory it lives in.  The loader parameter will be used to find the resource.
    *
    * Once the base of the resource is found, scan all files recursively within the base using the include and exclude
    * patterns.  A mirror file structure will be created within this AssembledDirectory. Ths is very useful when you
    * want to create a virtual jar that contains a subset of .class files in your classpath.
    *
    * The include/exclude patterns follow the Ant file pattern matching syntax.  See ant.apache.org for more details.
    *
    * @param baseResource
    * @param includes
    * @param excludes
    * @param loader
    */
   public void addResources(String baseResource, final String[] includes, final String[] excludes, ClassLoader loader)
   {
      URL url = loader.getResource(baseResource);
      if (url == null) throw new RuntimeException("Could not find baseResource: " + baseResource);
      String urlString = url.toString();
      int idx = urlString.lastIndexOf(baseResource);
      urlString = urlString.substring(0, idx);
      try
      {
         url = new URL(urlString);
         VirtualFile parent = VFS.getRoot(url);

         VisitorAttributes va = new VisitorAttributes();
         va.setLeavesOnly(true);
         SuffixesExcludeFilter noJars = new SuffixesExcludeFilter(JarUtils.getSuffixes());
         va.setRecurseFilter(noJars);

         VirtualFileFilter filter = new VirtualFileFilter()
         {

            public boolean accepts(VirtualFile file)
            {
               boolean matched = false;
               String path = file.getPathName();
               for (String include : includes)
               {
                  if (antMatch(path, include))
                  {
                     matched = true;
                     break;
                  }
               }
               if (!matched) return false;
               if (excludes != null)
               {
                  for (String exclude : excludes)
                  {
                     if (antMatch(path, exclude)) return false;
                  }
               }
               return true;
            }

         };

         FilterVirtualFileVisitor visitor = new FilterVirtualFileVisitor(filter, va);
         parent.visit(visitor);
         List<VirtualFile> files = visitor.getMatched();
         for (VirtualFile vf : files)
         {
            mkdirs(vf.getPathName()).addChild(vf);
         }
      }
      catch (IOException e)
      {
         throw new RuntimeException(e);
      }
   }

   /**
    * Create a regular expression pattern from an Ant file matching pattern
    *
    * @param matcher
    * @return
    */
   public static Pattern getPattern(String matcher)
   {
      matcher = matcher.replace(".", "\\.");
      matcher = matcher.replace("*", ".*");
      matcher = matcher.replace("?", ".{1}");
      return Pattern.compile(matcher);

   }

   /**
    * Determine whether a given file path matches an Ant pattern.
    *
    * @param path
    * @param expression
    * @return
    */
   public static boolean antMatch(String path, String expression)
   {
      if (path.startsWith("/")) path = path.substring(1);
      if (expression.endsWith("/")) expression += "**";
      String[] paths = path.split("/");
      String[] expressions = expression.split("/");

      int x = 0, p = 0;
      Pattern pattern = getPattern(expressions[0]);

      for (p = 0; p < paths.length && x < expressions.length; p++)
      {
         if (expressions[x].equals("**"))
         {
            do
            {
               x++;
            } while (x < expressions.length && expressions[x].equals("**"));
            if (x == expressions.length) return true; // "**" with nothing after it
            pattern = getPattern(expressions[x]);
         }
         String element = paths[p];
         if (pattern.matcher(element).matches())
         {
            x++;
            if (x < expressions.length)
            {
               pattern = getPattern(expressions[x]);
            }
         }
         else if (!(x > 0 && expressions[x - 1].equals("**"))) // our previous isn't "**"
         {
            return false;
         }
      }
      if (p < paths.length) return false;
      if (x < expressions.length) return false;
      return true;
   }

   /**
    * Add a VirtualFile as a child to this AssembledDirectory.
    *
    * @param vf
    */
   public VirtualFile addChild(VirtualFile vf)
   {
      return directory.addChild(vf.getHandler()).getVirtualFile();
   }

   /**
    * Add a VirtualFile as a child to this AssembledDirectory.  This file will be added
    * under a new aliased name.
    *
    * @param vf
    * @param newName
    */
   public VirtualFile addChild(VirtualFile vf, String newName)
   {
      try
      {
         AssembledFileHandler handler = new AssembledFileHandler((AssembledContext) directory.getVFSContext(), directory, newName, vf.getHandler());
         directory.addChild(handler);
         return handler.getVirtualFile();
      }
      catch (IOException e)
      {
         throw new RuntimeException(e);
      }
   }

   /**
    * Add a classloader found resource to as a child to this AssembledDirectory.  The base file name of the
    * resource will be used as the child's name.
    *
    * Thread.currentThread.getCOntextClassLoader() will be used to load the resource.
    *
    * @param resource
    */
   public VirtualFile addResource(String resource)
   {
      return addResource(resource, Thread.currentThread().getContextClassLoader());
   }

   /**
    * Add a classloader found resource to as a child to this AssembledDirectory.  The newName parameter will be used
    * as the name of the child.
    *
    * Thread.currentThread.getCOntextClassLoader() will be used to load the resource.
    *
    * @param resource
    * @param newName
    */
   public VirtualFile addResource(String resource, String newName)
   {
      return addResource(resource, Thread.currentThread().getContextClassLoader(), newName);
   }

   /**
    * Add a classloader found resource to as a child to this AssembledDirectory.  The base file name of the
    * resource will be used as the child's name.
    *
    * The loader parameter will be used to load the resource.
    *
    * @param resource
    * @param loader
    */
   public VirtualFile addResource(String resource, ClassLoader loader)
   {
      URL url = loader.getResource(resource);
      if (url == null) throw new RuntimeException("Could not find resource: " + resource);

      return addResource(url);
   }

   /**
    * Add a resource identified by the URL as a child to this AssembledDirectory.
    *
    * @param url
    */
   public VirtualFile addResource(URL url)
   {
      try
      {
         VirtualFile vf = VFS.getRoot(url);
         return addChild(vf);
      }
      catch (IOException e)
      {
         throw new RuntimeException(e);
      }
   }

   /**
    * Add a classloader found resource to as a child to this AssembledDirectory.  The newName parameter will be used
    * as the name of the child.
    *
    * The loader parameter will be used to load the resource.
    *
    * @param resource
    * @param loader
    * @param newName
    */
   public VirtualFile addResource(String resource, ClassLoader loader, String newName)
   {
      URL url = loader.getResource(resource);
      if (url == null) throw new RuntimeException("Could not find resource: " + resource);
      try
      {
         VirtualFile vf = VFS.getRoot(url);
         return addChild(vf, newName);
      }
      catch (IOException e)
      {
         throw new RuntimeException(e);
      }
   }

   /**
    * Add raw bytes as a file to this assembled directory
    *
    *
    * @param bytes
    * @param name
    * @return
    */
   public VirtualFile addBytes(byte[] bytes, String name)
   {
      ByteArrayHandler handler = null;
      try
      {
         handler = new ByteArrayHandler((AssembledContext) directory.getVFSContext(), directory, name, bytes);
      }
      catch (IOException e)
      {
         throw new RuntimeException(e);
      }
      directory.addChild(handler);
      return handler.getVirtualFile();
   }

   /**
    * Create a directory within this directory.
    *
    * @param name
    * @return
    */
   public AssembledDirectory mkdir(String name)
   {
      AssembledDirectoryHandler handler = null;
      try
      {
         handler = new AssembledDirectoryHandler((AssembledContext) directory.getVFSContext(), directory, name);
         directory.addChild(handler);
      }
      catch (IOException e)
      {
         throw new RuntimeException(e);
      }
      return new AssembledDirectory(handler);
   }

}
