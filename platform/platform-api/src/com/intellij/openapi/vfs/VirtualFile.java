/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.intellij.openapi.vfs;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.util.*;
import com.intellij.openapi.vfs.encoding.EncodingManager;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Collections;

/**
 * Represents a file in <code>{@link VirtualFileSystem}</code>. A particular file is represented by the same
 * <code>VirtualFile</code> instance for the entire lifetime of the IntelliJ IDEA process, unless the file
 * is deleted, in which case {@link #isValid()} for the instance will return <code>false</code>.
 * <p/>
 * If an in-memory implementation of VirtualFile is required, LightVirtualFile from the com.intellij.testFramework
 * package (Extended API) can be used.
 *
 * @see VirtualFileSystem
 * @see VirtualFileManager
 */
public abstract class VirtualFile extends UserDataHolderBase implements ModificationTracker {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.vfs.VirtualFile");
  public static final Key<Object> REQUESTOR_MARKER = new Key<Object>("REQUESTOR_MARKER");
  private static final Key<byte[]> BOM_KEY = new Key<byte[]>("BOM");
  private static final Key<Charset> CHARSET_KEY = new Key<Charset>("CHARSET");
  public static final VirtualFile[] EMPTY_ARRAY = new VirtualFile[0];

  protected VirtualFile() {
  }

  /**
   * Gets the name of this file.
   *
   * @return file name
   */
  @NotNull
  @NonNls
  public abstract String getName();

  /**
   * Gets the {@link VirtualFileSystem} this file belongs to.
   *
   * @return the {@link VirtualFileSystem}
   */
  @NotNull
  public abstract VirtualFileSystem getFileSystem();

  /**
   * Gets the path of this file. Path is a string which uniquely identifies file within given
   * <code>{@link VirtualFileSystem}</code>. Format of the path depends on the concrete file system.
   * For <code>{@link LocalFileSystem}</code> it is an absoulute file path with file separator characters
   * (File.separatorChar) replaced to the forward slash ('/').
   *
   * @return the path
   */
  public abstract String getPath();

  /**
   * Gets the URL of this file. The URL is a string which uniquely identifies file in all file systems.
   * It has the following format: <code>&lt;protocol&gt;://&lt;path&gt;</code>.
   * <p/>
   * File can be found by its URL using {@link VirtualFileManager#findFileByUrl} method.
   *
   * @return the URL consisting of protocol and path
   * @see VirtualFileManager#findFileByUrl
   * @see VirtualFile#getPath
   * @see VirtualFileSystem#getProtocol
   */
  @NotNull
  public abstract String getUrl();

  /**
   * Fetches "presentable URL" of this file. "Presentable URL" is a string to be used for displaying this
   * file in the UI.
   *
   * @return the presentable URL.
   * @see VirtualFileSystem#extractPresentableUrl
   */
  public final String getPresentableUrl() {
    return getFileSystem().extractPresentableUrl(getPath());
  }

  /**
   * Used as a property name in the {@link VirtualFilePropertyEvent} fired when the name of a
   * {@link VirtualFile} changes.
   *
   * @see VirtualFileListener#propertyChanged
   * @see VirtualFilePropertyEvent#getPropertyName
   */
  @NonNls public static final String PROP_NAME = "name";

  /**
   * Used as a property name in the {@link VirtualFilePropertyEvent} fired when the encoding of a
   * {@link VirtualFile} changes.
   *
   * @see VirtualFileListener#propertyChanged
   * @see VirtualFilePropertyEvent#getPropertyName
   */
  @NonNls public static final String PROP_ENCODING = "encoding";

  /**
   * Used as a property name in the {@link VirtualFilePropertyEvent} fired when the write permission of a
   * {@link VirtualFile} changes.
   *
   * @see VirtualFileListener#propertyChanged
   * @see VirtualFilePropertyEvent#getPropertyName
   */
  @NonNls public static final String PROP_WRITABLE = "writable";

  /**
   * Gets the extension of this file. If file name contains '.' extension is the substring from the last '.'
   * to the end of the name, otherwise extension is null.
   *
   * @return the extension or null if file name doesn't contain '.'
   */
  @Nullable
  @NonNls
  public String getExtension() {
    String name = getName();
    int index = name.lastIndexOf('.');
    if (index < 0) return null;
    return name.substring(index + 1);
  }

  /**
   * Gets the file name without the extension. If file name contains '.' the substring till the last '.' is returned.
   * Otherwise the same value as <code>{@link #getName}</code> method returns is returned.
   *
   * @return the name without extension
   *         if there is no '.' in it
   */
  @NonNls
  @NotNull
  public String getNameWithoutExtension() {
    String name = getName();
    int index = name.lastIndexOf('.');
    if (index < 0) return name;
    return name.substring(0, index);
  }


  /**
   * Renames this file to the <code>newName</code>.<p>
   * This method should be only called within write-action.
   * See {@link Application#runWriteAction}.
   *
   * @param requestor any object to control who called this method. Note that
   *                  it is considered to be an external change if <code>requestor</code> is <code>null</code>.
   *                  See {@link VirtualFileEvent#getRequestor}
   * @param newName   the new file name
   * @throws IOException if file failed to be renamed
   */
  public void rename(Object requestor, @NotNull @NonNls String newName) throws IOException {
    if (getName().equals(newName)) return;
    if (!VfsUtil.isValidName(newName)) {
      throw new IOException(VfsBundle.message("file.invalid.name.error", newName));
    }

    getFileSystem().renameFile(requestor, this, newName);
  }

  /**
   * Checks whether this file has write permission. Note that this value may be cached and may differ from
   * the write permission of the physical file.
   *
   * @return <code>true</code> if this file is writable, <code>false</code> otherwise
   */
  public abstract boolean isWritable();

  /**
   * Checks whether this file is a directory.
   *
   * @return <code>true</code> if this file is a directory, <code>fasle</code> otherwise
   */
  public abstract boolean isDirectory();

  /**
   * Checks whether this <code>VirtualFile</code> is valid. File can be invalidated either by deleting it or one of its
   * parents with {@link #delete} method or by an external change.
   * If file is not valid only {@link #equals}, {@link #hashCode} and methods from
   * {@link UserDataHolder} can be called for it. Using any other methods for an invalid {@link VirtualFile} instance
   * produce inpredictable results.
   *
   * @return <code>true</code> if this is a valid file, <code>fasle</code> otherwise
   */
  public abstract boolean isValid();

  /**
   * Gets the parent <code>VirtualFile</code>.
   *
   * @return the parent file or <code>null</code> if this file is a root directory
   */
  public abstract VirtualFile getParent();

  /**
   * Gets the child files.
   *
   * @return array of the child files or <code>null</code> if this file is not a directory
   */
  public abstract VirtualFile[] getChildren();

  /**
   * Finds child of this file with the given name.
   *
   * @param name the file name to search by
   * @return the file if found any, <code>null</code> otherwise
   */
  @Nullable
  public VirtualFile findChild(@NotNull @NonNls String name) {
    VirtualFile[] children = getChildren();
    if (children == null) return null;
    for (VirtualFile child : children) {
      if (child.nameEquals(name)) {
        return child;
      }
    }
    return null;
  }

  @Nullable
  public VirtualFile findOrCreateChildData(Object requestor, @NotNull @NonNls String name) throws IOException {
    final VirtualFile child = findChild(name);
    if (child != null) return child;
    return createChildData(requestor, name);
  }

  public Icon getIcon() {
    return getFileType().getIcon();
  }

  /**
   * @return the {@link FileType} of this file.
   *         When IDEA has no idea what the file type is (i.e. file type is not registered via {@link FileTypeManager}),
   *         it returns {@link com.intellij.openapi.fileTypes.FileTypes#UNKNOWN}
   */
  @NotNull
  public FileType getFileType() {
    return FileTypeManager.getInstance().getFileTypeByFile(this);
  }

  /**
   * Finds file by path relative to this file.
   *
   * @param relPath the relative path to search by
   * @return the file if found any, <code>null</code> otherwise
   */
  @Nullable
  public VirtualFile findFileByRelativePath(@NotNull @NonNls String relPath) {
    if (relPath.length() == 0) return this;

    int index = relPath.indexOf('/');
    if (index < 0) index = relPath.length();
    String name = relPath.substring(0, index);

    VirtualFile child;
    if (name.equals(".")) {
      child = this;
    }
    else if (name.equals("..")) {
      child = getParent();
    }
    else {
      child = findChild(name);
    }

    if (child == null) return null;

    if (index < relPath.length()) {
      return child.findFileByRelativePath(relPath.substring(index + 1));
    }
    else {
      return child;
    }
  }

  /**
   * Creates a subdirectory in this directory. This method should be only called within write-action.
   * See {@link com.intellij.openapi.application.Application#runWriteAction}.
   *
   * @param requestor any object to control who called this method. Note that
   *                  it is considered to be an external change if <code>requestor</code> is <code>null</code>.
   *                  See {@link VirtualFileEvent#getRequestor}
   * @param name      directory name
   * @return <code>VirtualFile</code> representing the created directory
   * @throws java.io.IOException if directory failed to be created
   */
  public VirtualFile createChildDirectory(Object requestor, @NonNls String name) throws IOException {
    if (!isDirectory()) {
      throw new IOException(VfsBundle.message("directory.create.wrong.parent.error"));
    }

    if (!isValid()) {
      throw new IOException(VfsBundle.message("invalid.directory.create.files"));
    }

    if (!VfsUtil.isValidName(name)) {
      throw new IOException(VfsBundle.message("directory.invalid.name.error", name));
    }

    if (findChild(name) != null) {
      throw new IOException(VfsBundle.message("file.create.already.exists.error", getUrl(), name));
    }

    return getFileSystem().createChildDirectory(requestor, this, name);
  }

  /**
   * Creates a new file in this directory. This method should be only called within write-action.
   * See {@link com.intellij.openapi.application.Application#runWriteAction}.
   *
   * @param requestor any object to control who called this method. Note that
   *                  it is considered to be an external change if <code>requestor</code> is <code>null</code>.
   *                  See {@link VirtualFileEvent#getRequestor}
   * @return <code>VirtualFile</code> representing the created file
   * @throws IOException if file failed to be created
   */
  public VirtualFile createChildData(Object requestor, @NotNull @NonNls String name) throws IOException {
    if (!isDirectory()) {
      throw new IOException(VfsBundle.message("file.create.wrong.parent.error"));
    }

    if (!isValid()) {
      throw new IOException(VfsBundle.message("invalid.directory.create.files"));
    }

    if (!VfsUtil.isValidName(name)) {
      throw new IOException(VfsBundle.message("file.invalid.name.error", name));
    }

    if (findChild(name) != null) {
      throw new IOException(VfsBundle.message("file.create.already.exists.error", getUrl(), name));
    }

    return getFileSystem().createChildFile(requestor, this, name);
  }

  /**
   * Deletes this file. This method should be only called within write-action.
   * See {@link com.intellij.openapi.application.Application#runWriteAction}.
   *
   * @param requestor any object to control who called this method. Note that
   *                  it is considered to be an external change if <code>requestor</code> is <code>null</code>.
   *                  See {@link VirtualFileEvent#getRequestor}
   * @throws IOException if file failed to be deleted
   */
  public void delete(Object requestor) throws IOException {
    LOG.assertTrue(isValid(), "Deleting invalid file");
    getFileSystem().deleteFile(requestor, this);
  }

  /**
   * Moves this file to another directory. This method should be only called within write-action.
   * See {@link com.intellij.openapi.application.Application#runWriteAction}.
   *
   * @param requestor any object to control who called this method. Note that
   *                  it is considered to be an external change if <code>requestor</code> is <code>null</code>.
   *                  See {@link VirtualFileEvent#getRequestor}
   * @param newParent the directory to move this file to
   * @throws IOException if file failed to be moved
   */
  public void move(final Object requestor, final VirtualFile newParent) throws IOException {
    if (getFileSystem() != newParent.getFileSystem()) {
      throw new IOException(VfsBundle.message("file.move.error", newParent.getPresentableUrl()));
    }

    VfsUtil.doActionAndRestoreEncoding(this, new ThrowableComputable<VirtualFile, IOException>() {
      public VirtualFile compute() throws IOException {
        getFileSystem().moveFile(requestor, VirtualFile.this, newParent);
        return VirtualFile.this;
      }
    });
  }

  public VirtualFile copy(final Object requestor, final VirtualFile newParent, final String copyName) throws IOException {
    if (getFileSystem() != newParent.getFileSystem()) {
      throw new IOException(VfsBundle.message("file.copy.error", newParent.getPresentableUrl()));
    }

    if (!newParent.isDirectory()) {
      throw new IOException(VfsBundle.message("file.copy.target.must.be.directory"));
    }

    return VfsUtil.doActionAndRestoreEncoding(this, new ThrowableComputable<VirtualFile, IOException>() {
      public VirtualFile compute() throws IOException {
        return getFileSystem().copyFile(requestor, VirtualFile.this, newParent, copyName);
      }
    });
  }


  public final void setBinaryContent(byte[] content) throws IOException {
    setBinaryContent(content, -1, -1);
  }

  /**
   * @return Retreive the charset file have been loaded with (if loaded) and would be saved with (if would).
   */
  public Charset getCharset() {
    Charset charset = getUserData(CHARSET_KEY);
    if (charset == null) {
      charset = EncodingManager.getInstance().getDefaultCharset();
      setCharset(charset);
    }
    return charset;
  }

  public void setCharset(final Charset charset) {
    final Charset old = getUserData(CHARSET_KEY);
    putUserData(CHARSET_KEY, charset);
    byte[] bom = charset == null ? null : CharsetToolkit.getBom(charset);
    setBOM(bom);

    if (old != null && !old.equals(charset)) { //do not send on detect
      final Application application = ApplicationManager.getApplication();
      application.invokeLater(new Runnable() {
        public void run() {
          if (isValid() && !application.isDisposed()) {
            application.runWriteAction(new Runnable(){
              public void run() {
                application.getMessageBus().syncPublisher(VirtualFileManager.VFS_CHANGES).after(Collections.singletonList(new VFilePropertyChangeEvent(this, VirtualFile.this, PROP_ENCODING, old, charset, false)));
              }
            });
          }
        }
      }, ModalityState.NON_MODAL);
    }
  }

  protected boolean isCharsetSet() {
    return getUserData(CHARSET_KEY) != null;
  }

  public void setBinaryContent(final byte[] content, long newModificationStamp, long newTimeStamp) throws IOException {
    OutputStream outputStream = null;
    try {
      outputStream = getOutputStream(this, newModificationStamp, newTimeStamp);
      outputStream.write(content);
      outputStream.flush();
    }
    finally {
      if (outputStream != null) outputStream.close();
    }
  }

  /**
   * Gets the <code>OutputStream</code> for this file.
   *
   * @param requestor any object to control who called this method. Note that
   *                  it is considered to be an external change if <code>requestor</code> is <code>null</code>.
   *                  See {@link VirtualFileEvent#getRequestor}
   * @return <code>OutputStream</code>
   * @throws IOException if an I/O error occurs
   */
  public final OutputStream getOutputStream(Object requestor) throws IOException {
    return getOutputStream(requestor, -1, -1);
  }

  /**
   * Gets the <code>OutputStream</code> for this file and sets modification stamp and time stamp to the specified values
   * after closing the stream.<p>
   * <p/>
   * Normally you should not use this method.
   *
   * @param requestor            any object to control who called this method. Note that
   *                             it is considered to be an external change if <code>requestor</code> is <code>null</code>.
   *                             See {@link VirtualFileEvent#getRequestor}
   * @param newModificationStamp new modification stamp or -1 if no special value should be set
   * @param newTimeStamp         new time stamp or -1 if no special value should be set
   * @return <code>OutputStream</code>
   * @throws IOException if an I/O error occurs
   * @see #getModificationStamp()
   */
  @NotNull
  public abstract OutputStream getOutputStream(Object requestor, long newModificationStamp, long newTimeStamp) throws IOException;

  /**
   * Returns file content as an array of bytes.
   *
   * @return file content
   * @throws IOException if an I/O error occurs
   * @see #getInputStream()
   */
  @NotNull
  public abstract byte[] contentsToByteArray() throws IOException;


  /**
   * Gets modification stamp value. Modification stamp is a value changed by any modification
   * of the content of the file. Note that it is not related to the file modification time.
   *
   * @return modification stamp
   * @see #getTimeStamp()
   */
  public long getModificationStamp() {
    throw new UnsupportedOperationException();
  }

  /**
   * Gets the timestamp for this file. Note that this value may be cached and may differ from
   * the timestamp of the physical file.
   *
   * @return timestamp
   * @see java.io.File#lastModified
   */
  public abstract long getTimeStamp();

  /**
   * File length in bytes.
   *
   * @return the length of this file.
   */
  public abstract long getLength();

  /**
   * Refreshes the cached file information from the physical file system. If this file is not a directory
   * the timestamp value is refreshed and <code>contentsChanged</code> event is fired if it is changed.<p>
   * If this file is a directory the set of its children is refreshed. If recursive value is <code>true</code> all
   * children are refreshed recursively.
   * <p/>
   * This method should be only called within write-action.
   * See {@link com.intellij.openapi.application.Application#runWriteAction}.
   *
   * @param asynchronous if <code>true</code> then the operation will be performed in a separate thread,
   *                     otherwise will be performed immediately
   * @param recursive    whether to refresh all the files in this directory recursively
   */
  public void refresh(boolean asynchronous, boolean recursive) {
    refresh(asynchronous, recursive, null);
  }

  /**
   * The same as {@link #refresh(boolean asynchronous, boolean recursive)} but also runs <code>postRunnable</code>
   * after the operation is completed.
   */
  public abstract void refresh(boolean asynchronous, boolean recursive, Runnable postRunnable);

  public String getPresentableName() {
    return getName();
  }

  public long getModificationCount() {
    return isValid() ? getTimeStamp() : -1;
  }

  /**
   * @param name
   * @return whether file name equals to this name
   *         result depends on the filesystem specifics
   */
  protected boolean nameEquals(@NotNull @NonNls String name) {
    return getName().equals(name);
  }

  /**
   * Gets the <code>InputStream</code> for this file.
   *
   * @return <code>InputStream</code>
   * @throws IOException if an I/O error occurs
   * @see #contentsToByteArray
   */
  public abstract InputStream getInputStream() throws IOException;

  @Nullable
  public byte[] getBOM() {
    return getUserData(BOM_KEY);
  }

  public void setBOM(@Nullable byte[] BOM) {
    putUserData(BOM_KEY, BOM);
  }

  @NonNls
  public String toString() {
    return "VirtualFile: " + getPresentableUrl();
  }

  public boolean exists() {
    return isValid();
  }

  public boolean isInLocalFileSystem() {
    return false;
  }
}
