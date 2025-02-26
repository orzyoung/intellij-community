// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.indexing.dependenciesCache;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.IndexableSetContributor;
import com.intellij.util.indexing.roots.IndexableEntityProvider.IndexableIteratorBuilder;
import com.intellij.util.indexing.roots.builders.IndexableIteratorBuilders;
import com.intellij.util.indexing.roots.builders.IndexableSetContributorFilesIteratorBuilder;
import com.intellij.util.indexing.roots.builders.SyntheticLibraryIteratorBuilder;
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileIndex;
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileKind;
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileSet;
import com.intellij.workspaceModel.core.fileIndex.impl.WorkspaceFileSetRecognizer;
import com.intellij.workspaceModel.ide.WorkspaceModel;
import com.intellij.workspaceModel.ide.legacyBridge.ModuleBridge;
import com.intellij.workspaceModel.storage.EntityReference;
import com.intellij.workspaceModel.storage.EntityStorage;
import com.intellij.workspaceModel.storage.bridgeEntities.LibraryId;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

@ApiStatus.Internal
final class RescannedRootsUtil {
  private static final Logger LOG = Logger.getInstance(RescannedRootsUtil.class);

  static Collection<? extends IndexableIteratorBuilder> getUnexcludedRootsIteratorBuilders(@NotNull Project project,
                                                                                           @NotNull List<? extends SyntheticLibraryDescriptor> libraryDescriptorsBefore,
                                                                                           @NotNull List<? extends ExcludePolicyDescriptor> excludedDescriptorsBefore,
                                                                                           @NotNull List<? extends SyntheticLibraryDescriptor> librariesDescriptorsAfter) {
    Set<VirtualFile> excludedRoots = new HashSet<>();
    for (SyntheticLibraryDescriptor value : libraryDescriptorsBefore) {
      excludedRoots.addAll(value.excludedRoots);
    }
    for (ExcludePolicyDescriptor value : excludedDescriptorsBefore) {
      excludedRoots.addAll(value.getExcludedRoots());
      excludedRoots.addAll(value.excludedFromSdkRoots);
    }

    return createBuildersForReincludedFiles(project, excludedRoots, librariesDescriptorsAfter);
  }

  @NotNull
  private static List<IndexableIteratorBuilder> createBuildersForReincludedFiles(@NotNull Project project,
                                                                                 @NotNull Collection<VirtualFile> reincludedRoots,
                                                                                 @NotNull List<? extends SyntheticLibraryDescriptor> librariesDescriptorsAfter) {
    if (reincludedRoots.isEmpty()) return Collections.emptyList();

    List<VirtualFile> filesFromIndexableSetContributors = new ArrayList<>();
    List<VirtualFile> filesFromAdditionalLibraryRootsProviders = new ArrayList<>();

    EntityStorage entityStorage = WorkspaceModel.getInstance(project).getEntityStorage().getCurrent();
    WorkspaceFileIndex workspaceFileIndex = WorkspaceFileIndex.getInstance(project);
    ArrayList<IndexableIteratorBuilder> result = new ArrayList<>();
    Iterator<VirtualFile> iterator = reincludedRoots.iterator();
    while (iterator.hasNext()) {
      VirtualFile file = iterator.next();
      WorkspaceFileSet fileSet = workspaceFileIndex.findFileSet(file, true, true, true, true);
      if (fileSet == null) {
        filesFromIndexableSetContributors.add(file);
        iterator.remove();
        continue;
      }

      if (fileSet.getKind() == WorkspaceFileKind.CONTENT || fileSet.getKind() == WorkspaceFileKind.TEST_CONTENT) {
        Module module = WorkspaceFileSetRecognizer.INSTANCE.getModuleForContent(fileSet);
        if (module != null) {
          result.addAll(IndexableIteratorBuilders.INSTANCE.forModuleRootsFileBased(((ModuleBridge)module).getModuleEntityId(),
                                                                                   Collections.singletonList(file)));
          iterator.remove();
          continue;
        }

        EntityReference<?> entityReference = WorkspaceFileSetRecognizer.INSTANCE.getEntityReference(fileSet);
        LOG.assertTrue(entityReference != null, "Content element's fileSet without entity reference, " + fileSet);
        result.addAll(IndexableIteratorBuilders.INSTANCE.forModuleUnawareContentEntity(entityReference, Collections.singletonList(file)));
        iterator.remove();
        continue;
      }

      //here we have WorkspaceFileKind.EXTERNAL or WorkspaceFileKind.EXTERNAL_SOURCE
      Collection<VirtualFile> roots =
        fileSet.getKind() == WorkspaceFileKind.EXTERNAL ? Collections.singletonList(file) : Collections.emptyList();
      Collection<VirtualFile> sourceRoots =
        fileSet.getKind() == WorkspaceFileKind.EXTERNAL_SOURCE ? Collections.singletonList(file) : Collections.emptyList();

      Sdk sdk = WorkspaceFileSetRecognizer.INSTANCE.getSdk(fileSet);
      if (sdk != null) {
        result.addAll(IndexableIteratorBuilders.INSTANCE.forSdk(sdk, Collections.singletonList(file)));
        iterator.remove();
        continue;
      }

      if (WorkspaceFileSetRecognizer.INSTANCE.isFromAdditionalLibraryRootsProvider(fileSet)) {
        filesFromAdditionalLibraryRootsProviders.add(file);
        iterator.remove();
        continue;
      }

      LibraryId libraryId = WorkspaceFileSetRecognizer.INSTANCE.getLibraryId(fileSet, entityStorage);
      if (libraryId != null) {
        result.addAll(IndexableIteratorBuilders.INSTANCE.forLibraryEntity(libraryId, true, roots, sourceRoots));
        iterator.remove();
        continue;
      }

      EntityReference<?> entityReference = WorkspaceFileSetRecognizer.INSTANCE.getEntityReference(fileSet);
      LOG.assertTrue(entityReference != null, "External element's fileSet without entity reference, " + fileSet);
      IndexableIteratorBuilders.INSTANCE.forExternalEntity(entityReference, roots, sourceRoots);
      iterator.remove();
    }

    if (!filesFromAdditionalLibraryRootsProviders.isEmpty()) {
      result.addAll(createSyntheticLibraryIteratorBuilders(librariesDescriptorsAfter, filesFromAdditionalLibraryRootsProviders));
    }

    if (!filesFromIndexableSetContributors.isEmpty()) {
      for (IndexableSetContributor contributor : IndexableSetContributor.EP_NAME.getExtensionList()) {
        Set<VirtualFile> applicationRoots =
          collectAndRemoveFilesUnder(filesFromIndexableSetContributors, contributor.getAdditionalRootsToIndex());
        Set<VirtualFile> projectRoots =
          collectAndRemoveFilesUnder(filesFromIndexableSetContributors, contributor.getAdditionalProjectRootsToIndex(project));

        if (!applicationRoots.isEmpty()) {
          result.add(
            new IndexableSetContributorFilesIteratorBuilder(null, contributor.getDebugName(), applicationRoots, false, contributor));
        }
        if (!projectRoots.isEmpty()) {
          result.add(new IndexableSetContributorFilesIteratorBuilder(null, contributor.getDebugName(), projectRoots, true, contributor));
        }
        if (filesFromIndexableSetContributors.isEmpty()) {
          break;
        }
      }
    }

    if (!reincludedRoots.isEmpty()) {
      throw new IllegalStateException("Roots were not found: " + StringUtil.join(reincludedRoots, "\n"));
    }
    return result;
  }

  @NotNull
  private static Set<VirtualFile> collectAndRemoveFilesUnder(List<VirtualFile> fileToCheck, Set<VirtualFile> roots) {
    Iterator<VirtualFile> iterator = fileToCheck.iterator();
    Set<VirtualFile> applicationRoots = new HashSet<>();
    while (iterator.hasNext()) {
      VirtualFile next = iterator.next();
      if (VfsUtilCore.isUnder(next, roots)) {
        applicationRoots.add(next);
        iterator.remove();
      }
    }
    return applicationRoots;
  }

  private static Collection<SyntheticLibraryIteratorBuilder> createSyntheticLibraryIteratorBuilders(List<? extends SyntheticLibraryDescriptor> librariesDescriptorsAfter,
                                                                                                    Collection<VirtualFile> files) {
    List<SyntheticLibraryIteratorBuilder> builders = new ArrayList<>();
    for (SyntheticLibraryDescriptor lib : librariesDescriptorsAfter) {
      List<VirtualFile> roots = new ArrayList<>();
      Iterator<VirtualFile> iterator = files.iterator();
      while (iterator.hasNext()) {
        VirtualFile file = iterator.next();
        if (lib.contains(file)) {
          roots.add(file);
          iterator.remove();
        }
      }
      if (!roots.isEmpty()) {
        builders.add(new SyntheticLibraryIteratorBuilder(lib.library, lib.presentableLibraryName, roots));
      }
      if (files.isEmpty()) {
        return builders;
      }
    }
    LOG.error("Failed fo find SyntheticLibrary roots for " + StringUtil.join(files, "\n"));
    return builders;
  }

  @NotNull
  static Collection<? extends IndexableIteratorBuilder> getLibraryIteratorBuilders(@Nullable Collection<? extends SyntheticLibraryDescriptor> before,
                                                                                   @NotNull Collection<? extends SyntheticLibraryDescriptor> after) {
    if (after.size() == 1 && before != null && before.size() == 1) {
      SyntheticLibraryDescriptor afterLib = after.iterator().next();
      SyntheticLibraryDescriptor beforeLib = before.iterator().next();
      //fallback to logic for SyntheticLibrary without comparisonId & excludeFileCondition
      if (afterLib.comparisonId == null && beforeLib.comparisonId == null &&
          !afterLib.hasExcludeFileCondition && !beforeLib.hasExcludeFileCondition) {
        return createLibraryIteratorBuilders(beforeLib, afterLib);
      }
    }
    List<IndexableIteratorBuilder> result = new ArrayList<>();
    for (SyntheticLibraryDescriptor afterLib : after) {
      SyntheticLibraryDescriptor libForIncrementalRescanning = afterLib.getLibForIncrementalRescanning(before);
      if (libForIncrementalRescanning != null) {
        result.addAll(createLibraryIteratorBuilders(libForIncrementalRescanning, afterLib));
      }
      else {
        result.add(new SyntheticLibraryIteratorBuilder(afterLib.library, afterLib.presentableLibraryName, afterLib.getAllRoots()));
      }
    }
    return result;
  }

  @NotNull
  private static List<? extends IndexableIteratorBuilder> createLibraryIteratorBuilders(@NotNull SyntheticLibraryDescriptor beforeLib,
                                                                                        @NotNull SyntheticLibraryDescriptor afterLib) {
    Collection<VirtualFile> newRoots = ContainerUtil.subtract(afterLib.getAllRoots(), beforeLib.getAllRoots());
    if (!newRoots.isEmpty()) {
      return Collections.singletonList(
        new SyntheticLibraryIteratorBuilder(afterLib.library, afterLib.presentableLibraryName, newRoots));
    }
    else {
      return Collections.emptyList();
    }
  }

  @NotNull
  public static Collection<? extends IndexableIteratorBuilder> getIndexableSetIteratorBuilders(@Nullable IndexableSetContributorDescriptor before,
                                                                                               @NotNull IndexableSetContributorDescriptor after) {
    if (before == null) {
      return after.toIteratorBuilders();
    }
    Set<VirtualFile> applicationRootsToIndex = subtract(after.applicationRoots, before.applicationRoots);
    Set<VirtualFile> projectRootsToIndex = subtract(after.projectRoots, before.projectRoots);

    List<IndexableIteratorBuilder> result = new ArrayList<>(2);
    if (!projectRootsToIndex.isEmpty()) {
      result.add(after.toIteratorBuilderWithRoots(projectRootsToIndex, true));
    }
    if (!applicationRootsToIndex.isEmpty()) {
      result.add(after.toIteratorBuilderWithRoots(applicationRootsToIndex, false));
    }
    return result;
  }

  private static @NotNull <T> Set<T> subtract(@NotNull Collection<? extends T> from, @NotNull Collection<? extends T> what) {
    Set<T> set = new HashSet<>(from);
    set.removeAll(what);
    return set;
  }
}