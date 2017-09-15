// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe.packages;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.AstAfterPreprocessing;
import com.google.devtools.build.lib.packages.AttributeContainer;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.CachingPackageLocator;
import com.google.devtools.build.lib.packages.ConstantRuleVisibility;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.PackageFactory.EnvironmentExtension;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.skyframe.ASTFileLookupFunction;
import com.google.devtools.build.lib.skyframe.BlacklistedPackagePrefixesFunction;
import com.google.devtools.build.lib.skyframe.ContainingPackageLookupFunction;
import com.google.devtools.build.lib.skyframe.DirectoryListingFunction;
import com.google.devtools.build.lib.skyframe.DirectoryListingStateFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.ExternalPackageFunction;
import com.google.devtools.build.lib.skyframe.FileFunction;
import com.google.devtools.build.lib.skyframe.FileStateFunction;
import com.google.devtools.build.lib.skyframe.FileSymlinkCycleUniquenessFunction;
import com.google.devtools.build.lib.skyframe.FileSymlinkInfiniteExpansionUniquenessFunction;
import com.google.devtools.build.lib.skyframe.GlobFunction;
import com.google.devtools.build.lib.skyframe.PackageFunction;
import com.google.devtools.build.lib.skyframe.PackageFunction.ActionOnIOExceptionReadingBuildFile;
import com.google.devtools.build.lib.skyframe.PackageFunction.CacheEntryWithGlobDeps;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PackageLookupValue.BuildFileName;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.PrecomputedFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.SkylarkImportLookupFunction;
import com.google.devtools.build.lib.skyframe.WorkspaceASTFunction;
import com.google.devtools.build.lib.skyframe.WorkspaceFileFunction;
import com.google.devtools.build.lib.skyframe.WorkspaceNameFunction;
import com.google.devtools.build.lib.syntax.SkylarkSemanticsOptions;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.UnixGlob;
import com.google.devtools.build.skyframe.BuildDriver;
import com.google.devtools.build.skyframe.Differencer;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.ImmutableDiff;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.Injectable;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.SequentialBuildDriver;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.Version;
import com.google.devtools.build.skyframe.WalkableGraph;
import com.google.devtools.common.options.Options;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Abstract base class of a {@link PackageLoader} implementation that has no incrementality or
 * caching.
 */
public abstract class AbstractPackageLoader implements PackageLoader {
  private final ImmutableDiff preinjectedDiff;
  private final Differencer preinjectedDifferencer = new Differencer() {
    @Override
    public Diff getDiff(WalkableGraph fromGraph, Version fromVersion, Version toVersion)
        throws InterruptedException {
      return preinjectedDiff;
    }
  };
  private final Reporter reporter;
  protected final RuleClassProvider ruleClassProvider;
  protected final ImmutableMap<SkyFunctionName, SkyFunction> extraSkyFunctions;
  protected final AtomicReference<PathPackageLocator> pkgLocatorRef;
  protected final ExternalFilesHelper externalFilesHelper;
  protected final CachingPackageLocator packageManager;
  protected final BlazeDirectories directories;
  private final int legacyGlobbingThreads;
  private final int skyframeThreads;

  /** Abstract base class of a builder for {@link PackageLoader} instances. */
  public abstract static class Builder {
    protected final Path workspaceDir;
    protected RuleClassProvider ruleClassProvider = getDefaultRuleClassProvider();
    protected Reporter reporter = new Reporter(new EventBus());
    protected Map<SkyFunctionName, SkyFunction> extraSkyFunctions = new HashMap<>();
    protected List<PrecomputedValue.Injected> extraPrecomputedValues = new ArrayList<>();
    protected String defaultsPackageContents = getDefaultDefaultPackageContents();
    protected int legacyGlobbingThreads = 1;
    int skyframeThreads = 1;

    protected Builder(Path workspaceDir) {
      this.workspaceDir = workspaceDir;
    }

    public Builder setRuleClassProvider(RuleClassProvider ruleClassProvider) {
      this.ruleClassProvider = ruleClassProvider;
      return this;
    }

    public Builder setReporter(Reporter reporter) {
      this.reporter = reporter;
      return this;
    }

    public Builder addExtraSkyFunctions(
        ImmutableMap<SkyFunctionName, SkyFunction> extraSkyFunctions) {
      this.extraSkyFunctions.putAll(extraSkyFunctions);
      return this;
    }

    public Builder addExtraPrecomputedValues(PrecomputedValue.Injected... extraPrecomputedValues) {
      return this.addExtraPrecomputedValues(Arrays.asList(extraPrecomputedValues));
    }

    public Builder addExtraPrecomputedValues(
        List<PrecomputedValue.Injected> extraPrecomputedValues) {
      this.extraPrecomputedValues.addAll(extraPrecomputedValues);
      return this;
    }

    public Builder setLegacyGlobbingThreads(int numThreads) {
      this.legacyGlobbingThreads = numThreads;
      return this;
    }

    public Builder setSkyframeThreads(int skyframeThreads) {
      this.skyframeThreads = skyframeThreads;
      return this;
    }

    public abstract PackageLoader build();

    protected abstract RuleClassProvider getDefaultRuleClassProvider();

    protected abstract String getDefaultDefaultPackageContents();
  }

  protected AbstractPackageLoader(Builder builder) {
    Path workspaceDir = builder.workspaceDir;
    PathPackageLocator pkgLocator =
        new PathPackageLocator(null, ImmutableList.of(workspaceDir));
    this.ruleClassProvider = builder.ruleClassProvider;
    this.reporter = builder.reporter;
    this.extraSkyFunctions = ImmutableMap.copyOf(builder.extraSkyFunctions);
    this.pkgLocatorRef = new AtomicReference<>(pkgLocator);
    this.legacyGlobbingThreads = builder.legacyGlobbingThreads;
    this.skyframeThreads = builder.skyframeThreads;

    // The 'installBase' and 'outputBase' directories won't be meaningfully used by
    // WorkspaceFileFunction, so we pass in a dummy Path.
    // TODO(nharmata): Refactor WorkspaceFileFunction to make this a non-issue.
    Path devNull = workspaceDir.getFileSystem().getPath("/dev/null");
    this.directories =
        new BlazeDirectories(new ServerDirectories(devNull, devNull), workspaceDir, "blaze");
    this.externalFilesHelper = new ExternalFilesHelper(
        pkgLocatorRef,
        ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
        directories);
    this.packageManager = new CachingPackageLocator() {
      @Override
      @Nullable
      public Path getBuildFileForPackage(PackageIdentifier packageName) {
        return pkgLocatorRef.get().getPackageBuildFileNullable(packageName,
            UnixGlob.DEFAULT_SYSCALLS_REF);
      }
    };
    this.preinjectedDiff =
        makePreinjectedDiff(
            pkgLocator,
            builder.defaultsPackageContents,
            ImmutableList.copyOf(builder.extraPrecomputedValues),
            directories);
  }

  private static ImmutableDiff makePreinjectedDiff(
      PathPackageLocator pkgLocator,
      String defaultsPackageContents,
      ImmutableList<PrecomputedValue.Injected> extraPrecomputedValues,
      BlazeDirectories directories) {
    final Map<SkyKey, SkyValue> valuesToInject = new HashMap<>();
    Injectable injectable =
        new Injectable() {
          @Override
          public void inject(Map<SkyKey, ? extends SkyValue> values) {
            valuesToInject.putAll(values);
          }

          @Override
          public void inject(SkyKey key, SkyValue value) {
            valuesToInject.put(key, value);
          }
        };
    for (PrecomputedValue.Injected injected : extraPrecomputedValues) {
      injected.inject(injectable);
    }
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(injectable, pkgLocator);
    PrecomputedValue.DEFAULT_VISIBILITY.set(injectable, ConstantRuleVisibility.PRIVATE);
    PrecomputedValue.SKYLARK_SEMANTICS.set(
        injectable,
        Options.getDefaults(SkylarkSemanticsOptions.class));
    PrecomputedValue.DEFAULTS_PACKAGE_CONTENTS.set(injectable, defaultsPackageContents);
    PrecomputedValue.BLACKLISTED_PACKAGE_PREFIXES_FILE.set(injectable, PathFragment.EMPTY_FRAGMENT);
    PrecomputedValue.BLAZE_DIRECTORIES.set(injectable, directories);
    return new ImmutableDiff(ImmutableList.<SkyKey>of(), valuesToInject);
  }

  @Override
  public Package loadPackage(PackageIdentifier pkgId)
      throws NoSuchPackageException, InterruptedException {
    return loadPackages(ImmutableList.of(pkgId)).get(pkgId).get();
  }

  @Override
  public ImmutableMap<PackageIdentifier, PackageLoader.PackageOrException> loadPackages(
      Iterable<? extends PackageIdentifier> pkgIds) throws InterruptedException {
    ImmutableList.Builder<SkyKey> keysBuilder = ImmutableList.builder();
    for (PackageIdentifier pkgId : pkgIds) {
      keysBuilder.add(PackageValue.key(pkgId));
    }
    ImmutableList<SkyKey> keys = keysBuilder.build();

    EvaluationResult<PackageValue> evalResult =
        makeFreshDriver().evaluate(keys, /*keepGoing=*/ true, skyframeThreads, reporter);

    ImmutableMap.Builder<PackageIdentifier, PackageLoader.PackageOrException> result =
        ImmutableMap.builder();
    for (SkyKey key : keys) {
      ErrorInfo error = evalResult.getError(key);
      PackageValue packageValue = evalResult.get(key);
      checkState((error == null) != (packageValue == null));
      PackageIdentifier pkgId = (PackageIdentifier) key.argument();
      result.put(
          pkgId,
          error != null
              ? new PackageOrException(null, exceptionFromErrorInfo(error, pkgId))
              : new PackageOrException(packageValue.getPackage(), null));
    }

    return result.build();
  }

  private static NoSuchPackageException exceptionFromErrorInfo(
      ErrorInfo error, PackageIdentifier pkgId) {
    if (!Iterables.isEmpty(error.getCycleInfo())) {
      return new BuildFileContainsErrorsException(
          pkgId, "Cycle encountered while loading package " + pkgId);
    }
    Throwable e = Preconditions.checkNotNull(error.getException());
    if (e instanceof NoSuchPackageException) {
      return (NoSuchPackageException) e;
    }
    throw new IllegalStateException(
        "Unexpected Exception type from PackageValue for '"
            + pkgId
            + "'' with root causes: "
            + Iterables.toString(error.getRootCauses()),
        e);
  }

  private BuildDriver makeFreshDriver() {
    return new SequentialBuildDriver(
        InMemoryMemoizingEvaluator.SUPPLIER.create(
            makeFreshSkyFunctions(),
            preinjectedDifferencer,
            /*progressReceiver=*/ null,
            new MemoizingEvaluator.EmittedEventState(),
            /*keepEdges=*/ false));
  }

  protected abstract String getName();
  protected abstract ImmutableList<EnvironmentExtension> getEnvironmentExtensions();
  protected abstract CrossRepositoryLabelViolationStrategy
      getCrossRepositoryLabelViolationStrategy();
  protected abstract ImmutableList<BuildFileName> getBuildFilesByPriority();
  protected abstract ActionOnIOExceptionReadingBuildFile getActionOnIOExceptionReadingBuildFile();

  protected final ImmutableMap<SkyFunctionName, SkyFunction> makeFreshSkyFunctions() {
    AtomicReference<TimestampGranularityMonitor> tsgm =
        new AtomicReference<>(new TimestampGranularityMonitor(BlazeClock.instance()));
    Cache<PackageIdentifier, CacheEntryWithGlobDeps<Package.Builder>> packageFunctionCache =
        CacheBuilder.newBuilder().build();
    Cache<PackageIdentifier, CacheEntryWithGlobDeps<AstAfterPreprocessing>> astCache =
        CacheBuilder.newBuilder().build();
    PackageFactory pkgFactory =
        new PackageFactory(
            ruleClassProvider,
            null,
            AttributeContainer::new,
            getEnvironmentExtensions(),
            getName(),
            Package.Builder.DefaultHelper.INSTANCE);
    pkgFactory.setGlobbingThreads(legacyGlobbingThreads);
    ImmutableMap.Builder<SkyFunctionName, SkyFunction> builder = ImmutableMap.builder();
    builder
        .put(SkyFunctions.PRECOMPUTED, new PrecomputedFunction())
        .put(SkyFunctions.FILE_STATE, new FileStateFunction(tsgm, externalFilesHelper))
        .put(
            SkyFunctions.DIRECTORY_LISTING_STATE,
            new DirectoryListingStateFunction(externalFilesHelper))
        .put(SkyFunctions.FILE_SYMLINK_CYCLE_UNIQUENESS, new FileSymlinkCycleUniquenessFunction())
        .put(
            SkyFunctions.FILE_SYMLINK_INFINITE_EXPANSION_UNIQUENESS,
            new FileSymlinkInfiniteExpansionUniquenessFunction())
        .put(SkyFunctions.FILE, new FileFunction(pkgLocatorRef))
        .put(SkyFunctions.DIRECTORY_LISTING, new DirectoryListingFunction())
        .put(
            SkyFunctions.PACKAGE_LOOKUP,
            new PackageLookupFunction(
                /* deletedPackages= */ new AtomicReference<>(ImmutableSet.<PackageIdentifier>of()),
                getCrossRepositoryLabelViolationStrategy(),
                getBuildFilesByPriority()))
        .put(SkyFunctions.BLACKLISTED_PACKAGE_PREFIXES, new BlacklistedPackagePrefixesFunction())
        .put(SkyFunctions.CONTAINING_PACKAGE_LOOKUP, new ContainingPackageLookupFunction())
        .put(SkyFunctions.AST_FILE_LOOKUP, new ASTFileLookupFunction(ruleClassProvider))
        .put(
            SkyFunctions.SKYLARK_IMPORTS_LOOKUP,
            new SkylarkImportLookupFunction(ruleClassProvider, pkgFactory))
        .put(SkyFunctions.WORKSPACE_NAME, new WorkspaceNameFunction())
        .put(SkyFunctions.WORKSPACE_AST, new WorkspaceASTFunction(ruleClassProvider))
        .put(
            SkyFunctions.WORKSPACE_FILE,
            new WorkspaceFileFunction(ruleClassProvider, pkgFactory, directories))
        .put(SkyFunctions.EXTERNAL_PACKAGE, new ExternalPackageFunction())
        .put(SkyFunctions.GLOB, new GlobFunction(/*alwaysUseDirListing=*/ false))
        .put(
            SkyFunctions.PACKAGE,
            new PackageFunction(
                pkgFactory,
                packageManager,
                /*showLoadingProgress=*/ new AtomicBoolean(false),
                packageFunctionCache,
                astCache,
                /*numPackagesLoaded=*/ new AtomicInteger(0),
                /*skylarkImportLookupFunctionForInlining=*/ null,
                /*packageProgress=*/ null,
                getActionOnIOExceptionReadingBuildFile()))
        .putAll(extraSkyFunctions);
    return builder.build();
  }
}
