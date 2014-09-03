// Copyright 2014 Google Inc. All rights reserved.
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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.CollectionUtils;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.rules.cpp.Link.LinkStaticness;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.util.ShellEscaper;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.RuleContext;
import com.google.devtools.build.lib.view.actions.CommandLine;
import com.google.devtools.build.lib.view.config.BuildConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Represents the command line of a linker invocation. It supports executables and dynamic
 * libraries as well as static libraries.
 */
@Immutable
public final class LinkCommandLine extends CommandLine {
  private final BuildConfiguration configuration;
  private final ActionOwner owner;
  private final Artifact output;
  @Nullable private final Artifact interfaceOutput;
  @Nullable private final Artifact symbolCountsOutput;
  private final ImmutableList<Artifact> buildInfoHeaderArtifacts;
  private final Iterable<? extends LinkerInput> linkerInputs;
  private final Iterable<? extends LinkerInput> runtimeInputs;
  private final LinkTargetType linkTargetType;
  private final LinkStaticness linkStaticness;
  private final ImmutableList<String> linkopts;
  private final ImmutableSet<String> features;
  private final ImmutableMap<Artifact, Artifact> linkstamps;
  @Nullable private final PathFragment runtimeSolibDir;
  private final boolean nativeDeps;
  private final boolean useExecOrigin;
  @Nullable private final Artifact interfaceSoBuilder;

  private LinkCommandLine(
      BuildConfiguration configuration,
      ActionOwner owner,
      Artifact output,
      @Nullable Artifact interfaceOutput,
      @Nullable Artifact symbolCountsOutput,
      ImmutableList<Artifact> buildInfoHeaderArtifacts,
      Iterable<? extends LinkerInput> linkerInputs,
      Iterable<? extends LinkerInput> runtimeInputs,
      LinkTargetType linkTargetType,
      LinkStaticness linkStaticness,
      ImmutableList<String> linkopts,
      ImmutableSet<String> features,
      ImmutableMap<Artifact, Artifact> linkstamps,
      @Nullable PathFragment runtimeSolibDir,
      boolean nativeDeps,
      boolean useExecOrigin,
      Artifact interfaceSoBuilder) {
    Preconditions.checkArgument(linkTargetType != LinkTargetType.INTERFACE_DYNAMIC_LIBRARY,
        "you can't link an interface dynamic library directly");
    if (linkTargetType != LinkTargetType.DYNAMIC_LIBRARY) {
      Preconditions.checkArgument(interfaceOutput == null,
          "interface output may only be non-null for dynamic library links");
    }
    if (linkTargetType.isStaticLibraryLink()) {
      Preconditions.checkArgument(linkstamps.isEmpty(),
          "linkstamps may only be present on dynamic library or executable links");
      Preconditions.checkArgument(linkStaticness == LinkStaticness.FULLY_STATIC,
          "static library link must be static");
      Preconditions.checkArgument(buildInfoHeaderArtifacts.isEmpty(),
          "build info headers may only be present on dynamic library or executable links");
      Preconditions.checkArgument(symbolCountsOutput == null,
          "the symbol counts output must be null for static links");
      Preconditions.checkArgument(runtimeSolibDir == null,
          "the runtime solib directory must be null for static links");
      Preconditions.checkArgument(!useExecOrigin,
          "the exec origin flag must be false for static links");
      Preconditions.checkArgument(!nativeDeps,
          "the native deps flag must be false for static links");
    }

    this.configuration = Preconditions.checkNotNull(configuration);
    this.owner = Preconditions.checkNotNull(owner);
    this.output = Preconditions.checkNotNull(output);
    this.interfaceOutput = interfaceOutput;
    this.symbolCountsOutput = symbolCountsOutput;
    this.buildInfoHeaderArtifacts = Preconditions.checkNotNull(buildInfoHeaderArtifacts);
    this.linkerInputs = Preconditions.checkNotNull(linkerInputs);
    this.runtimeInputs = Preconditions.checkNotNull(runtimeInputs);
    this.linkTargetType = Preconditions.checkNotNull(linkTargetType);
    this.linkStaticness = Preconditions.checkNotNull(linkStaticness);
    // For now, silently ignore linkopts if this is a static library link.
    this.linkopts = linkTargetType.isStaticLibraryLink()
        ? ImmutableList.<String>of()
        : Preconditions.checkNotNull(linkopts);
    this.features = Preconditions.checkNotNull(features);
    this.linkstamps = Preconditions.checkNotNull(linkstamps);
    this.runtimeSolibDir = runtimeSolibDir;
    this.nativeDeps = nativeDeps;
    this.useExecOrigin = useExecOrigin;
    // For now, silently ignore interfaceSoBuilder if we don't build an interface dynamic library.
    this.interfaceSoBuilder =
        ((linkTargetType == LinkTargetType.DYNAMIC_LIBRARY) && (interfaceOutput != null))
        ? Preconditions.checkNotNull(interfaceSoBuilder)
        : null;
  }

  /**
   * Returns an interface shared object output artifact produced during linking. This only returns
   * non-null if {@link #getLinkTargetType} is {@code DYNAMIC_LIBRARY} and an interface shared
   * object was requested.
   */
  @Nullable public Artifact getInterfaceOutput() {
    return interfaceOutput;
  }

  /**
   * Returns an artifact containing the number of symbols used per object file passed to the linker.
   * This is currently a gold only feature, and is only produced for executables. If another target
   * is being linked, or if symbol counts output is disabled, this will be null.
   */
  @Nullable public Artifact getSymbolCountsOutput() {
    return symbolCountsOutput;
  }

  /**
   * Returns the (ordered, immutable) list of header files that contain build info.
   */
  public ImmutableList<Artifact> getBuildInfoHeaderArtifacts() {
    return buildInfoHeaderArtifacts;
  }

  /**
   * Returns the (ordered, immutable) list of paths to the linker's input files.
   */
  public Iterable<? extends LinkerInput> getLinkerInputs() {
    return linkerInputs;
  }

  /**
   * Returns the runtime inputs to the linker.
   */
  public Iterable<? extends LinkerInput> getRuntimeInputs() {
    return runtimeInputs;
  }

  /**
   * Returns the current type of link target set.
   */
  public LinkTargetType getLinkTargetType() {
    return linkTargetType;
  }

  /**
   * Returns the "staticness" of the link.
   */
  public LinkStaticness getLinkStaticness() {
    return linkStaticness;
  }

  /**
   * Returns the additional linker options for this link.
   */
  public ImmutableList<String> getLinkopts() {
    return linkopts;
  }

  /**
   * Returns a (possibly empty) mapping of (C++ source file, .o output file) pairs for source files
   * that need to be compiled at link time.
   *
   * <p>This is used to embed various values from the build system into binaries to identify their
   * provenance.
   */
  public ImmutableMap<Artifact, Artifact> getLinkstamps() {
    return linkstamps;
  }

  /**
   * Returns the location of the C++ runtime solib symlinks. If null, the C++ dynamic runtime
   * libraries either do not exist (because they do not come from the depot) or they are in the
   * regular solib directory.
   */
  @Nullable public PathFragment getRuntimeSolibDir() {
    return runtimeSolibDir;
  }

  /**
   * Returns true for libraries linked as native dependencies for other languages.
   */
  public boolean isNativeDeps() {
    return nativeDeps;
  }

  /**
   * Returns true if this link should use $EXEC_ORIGIN as the root for finding shared libraries,
   * false if it should use $ORIGIN. See bug "Please use $EXEC_ORIGIN instead of $ORIGIN when
   * linking cc_tests" for further context.
   */
  public boolean useExecOrigin() {
    return useExecOrigin;
  }

  /**
   * Returns a raw link command for the given link invocation, including both command and
   * arguments (argv). After any further usage-specific processing, this can be passed to
   * {@link #finalizeWithLinkstampCommands} to give the final command line.
   *
   * @return raw link command line.
   */
  public List<String> getRawLinkArgv() {
    CppConfiguration cppConfig = configuration.getFragment(CppConfiguration.class);

    List<String> argv = new ArrayList<>();
    switch (linkTargetType) {
      case EXECUTABLE:
        addCppArgv(argv);
        break;

      case DYNAMIC_LIBRARY:
        if (interfaceOutput != null) {
          argv.add(configuration.getShExecutable().getPathString());
          argv.add("-c");
          argv.add("build_iface_so=\"$0\"; impl=\"$1\"; iface=\"$2\"; cmd=\"$3\"; shift 3; "
              + "\"$cmd\" \"$@\" && \"$build_iface_so\" \"$impl\" \"$iface\"");
          argv.add(interfaceSoBuilder.getExecPathString());
          argv.add(output.getExecPathString());
          argv.add(interfaceOutput.getExecPathString());
        }
        addCppArgv(argv);
        // -pie is not compatible with -shared and should be
        // removed when the latter is part of the link command. Should we need to further
        // distinguish between shared libraries and executables, we could add additional
        // command line / CROSSTOOL flags that distinguish them. But as long as this is
        // the only relevant use case we're just special-casing it here.
        Iterables.removeIf(argv, Predicates.equalTo("-pie"));
        break;

      case STATIC_LIBRARY:
      case PIC_STATIC_LIBRARY:
      case ALWAYS_LINK_STATIC_LIBRARY:
      case ALWAYS_LINK_PIC_STATIC_LIBRARY:
        // The static library link command follows this template:
        // ar <cmd> <output_archive> <input_files...>
        argv.add(cppConfig.getArExecutable().getPathString());
        argv.addAll(cppConfig.getArFlags(cppConfig.archiveType() == Link.ArchiveType.THIN));
        argv.add(output.getExecPathString());
        addInputFileLinkOptions(argv, /*needWholeArchive=*/false,
            /*includeLinkopts=*/false);
        break;

      default:
        throw new IllegalArgumentException();
    }

    // Fission mode: debug info is in .dwo files instead of .o files. Inform the linker of this.
    if (!linkTargetType.isStaticLibraryLink() && cppConfig.useFission()) {
      argv.add("-Wl,--gdb-index");
    }

    return argv;
  }

  @Override
  public List<String> arguments() {
    return finalizeWithLinkstampCommands(getRawLinkArgv());
  }

  /**
   * Takes a raw link command line and gives the final link command that will
   * also first compile any linkstamps necessary. Elements of rawLinkArgv are
   * shell-escaped.
   *
   * @param rawLinkArgv raw link command line
   *
   * @return final link command line suitable for execution
   */
  public List<String> finalizeWithLinkstampCommands(List<String> rawLinkArgv) {
    return addLinkstampingToCommand(getLinkstampCompileCommands(""), rawLinkArgv, true);
  }

  /**
   * Takes a raw link command line and gives the final link command that will also first compile any
   * linkstamps necessary. Elements of rawLinkArgv are not shell-escaped.
   *
   * @param rawLinkArgv raw link command line
   * @param outputPrefix prefix to add before the linkstamp outputs' exec paths
   *
   * @return final link command line suitable for execution
   */
  public List<String> finalizeAlreadyEscapedWithLinkstampCommands(
      List<String> rawLinkArgv, String outputPrefix) {
    return addLinkstampingToCommand(getLinkstampCompileCommands(outputPrefix), rawLinkArgv, false);
  }

  /**
   * Adds linkstamp compilation to the (otherwise) fully specified link
   * command if {@link #getLinkstamps} is non-empty.
   *
   * <p>Linkstamps were historically compiled implicitly as part of the link
   * command, but implicit compilation doesn't guarantee consistent outputs.
   * For example, the command "gcc input.o input.o foo/linkstamp.cc -o myapp"
   * causes gcc to implicitly run "gcc foo/linkstamp.cc -o /tmp/ccEtJHDB.o",
   * for some internally decided output path /tmp/ccEtJHDB.o, then add that path
   * to the linker's command line options. The name of this path can change
   * even between equivalently specified gcc invocations.
   *
   * <p>So now we explicitly compile these files in their own command
   * invocations before running the link command, thus giving us direct
   * control over the naming of their outputs. This method adds those extra
   * steps as necessary.
   * @param linkstampCommands individual linkstamp compilation commands
   * @param linkCommand the complete list of link command arguments (after
   *        .params file compacting) for an invocation
   * @param escapeArgs if true, linkCommand arguments are shell escaped. if
   *        false, arguments are returned as-is
   *
   * @return The original argument list if no linkstamps compilation commands
   *         are given, otherwise an expanded list that adds the linkstamp
   *         compilation commands and funnels their outputs into the link step.
   *         Note that these outputs only need to persist for the duration of
   *         the link step.
   */
  private static List<String> addLinkstampingToCommand(
      List<String> linkstampCommands,
      List<String> linkCommand,
      boolean escapeArgs) {
    if (linkstampCommands.isEmpty()) {
      return linkCommand;
    } else {
      List<String> batchCommand = Lists.newArrayListWithCapacity(3);
      batchCommand.add("/bin/bash");
      batchCommand.add("-c");
      batchCommand.add(
          Joiner.on(" && ").join(linkstampCommands) + " && "
          + (escapeArgs
              ? ShellEscaper.escapeJoinAll(linkCommand)
              : Joiner.on(" ").join(linkCommand)));
      return ImmutableList.copyOf(batchCommand);
    }
  }

  /**
   * Computes, for each C++ source file in
   * {@link #getLinkstamps}, the command necessary to compile
   * that file such that the output is correctly fed into the link command.
   *
   * <p>As these options (as well as all others) are taken into account when
   * computing the action key, they do not directly contain volatile build
   * information to avoid unnecessary relinking. Instead this information is
   * passed as an additional header generated by
   * {@link com.google.devtools.build.lib.rules.cpp.WriteBuildInfoHeaderAction}.
   *
   * @param outputPrefix prefix to add before the linkstamp outputs' exec paths
   * @return a list of shell-escaped compiler commmands, one for each entry
   *         in {@link #getLinkstamps}
   */
  public List<String> getLinkstampCompileCommands(String outputPrefix) {
    if (linkstamps.isEmpty()) {
      return ImmutableList.of();
    }

    CppConfiguration cppConfig = configuration.getFragment(CppConfiguration.class);
    String compilerCommand = cppConfig.getCppExecutable().getPathString();
    List<String> commands = Lists.newArrayListWithCapacity(linkstamps.size());

    for (Map.Entry<Artifact, Artifact> linkstamp : linkstamps.entrySet()) {
      List<String> optionList = new ArrayList<>();

      // Defines related to the build info are read from generated headers.
      for (Artifact header : buildInfoHeaderArtifacts) {
        optionList.add("-include");
        optionList.add(header.getExecPathString());
      }

      // G3_VERSION_INFO and G3_TARGET_NAME are C string literals that normally
      // contain the label of the target being linked.  However, they are set
      // differently when using shared native deps. In that case, a single .so file
      // is shared by multiple targets, and its contents cannot depend on which
      // target(s) were specified on the command line.  So in that case we have
      // to use the (obscure) name of the .so file instead, or more precisely
      // the path of the .so file relative to the workspace root.
      String targetLabel = isSharedNativeLibrary()
          ? output.getExecPathString()
          : Label.print(owner.getLabel());
      optionList.add("-DG3_VERSION_INFO=\"" + targetLabel + "\"");
      optionList.add("-DG3_TARGET_NAME=\"" + targetLabel + "\"");

      // G3_BUILD_TARGET is a C string literal containing the output of this
      // link.  (An undocumented and untested invariant is that G3_BUILD_TARGET is the location of
      // the executable, either absolutely, or relative to the directory part of BUILD_INFO.)
      optionList.add("-DG3_BUILD_TARGET=\"" + output.getExecPathString() + "\"");

      optionList.add("-DGPLATFORM=\"" + cppConfig + "\"");

      // Needed to find headers included from linkstamps.
      optionList.add("-I.");

      // Add sysroot.
      PathFragment sysroot = cppConfig.getSysroot();
      if (sysroot != null) {
        optionList.add("--sysroot=" + sysroot.getPathString());
      }

      // Add toolchain compiler options.
      optionList.addAll(cppConfig.getCompilerOptions(features));
      optionList.addAll(cppConfig.getCOptions());
      optionList.addAll(cppConfig.getUnfilteredCompilerOptions(features));

      // For dynamic libraries, produce position independent code.
      if (linkTargetType == LinkTargetType.DYNAMIC_LIBRARY && cppConfig.toolchainNeedsPic()) {
        optionList.add("-fPIC");
      }

      // Stamp FDO builds with FDO subtype string
      String fdoBuildStamp = CppHelper.getFdoBuildStamp(cppConfig);
      if (fdoBuildStamp != null) {
        optionList.add("-D" + CppConfiguration.FDO_STAMP_MACRO + "=\"" + fdoBuildStamp + "\"");
      }

      // Add the compilation target.
      optionList.add("-c");
      optionList.add(linkstamp.getKey().getExecPathString());

      // Assemble the final command, exempting outputPrefix from shell escaping.
      commands.add(compilerCommand + " "
          + ShellEscaper.escapeJoinAll(optionList)
          + " -o "
          + outputPrefix
          + ShellEscaper.escapeString(linkstamp.getValue().getExecPathString()));
    }

    return commands;
  }

  /**
   * Determine the arguments to pass to the C++ compiler when linking.
   * Add them to the {@code argv} parameter.
   */
  private void addCppArgv(List<String> argv) {
    CppConfiguration cppConfig = configuration.getFragment(CppConfiguration.class);

    argv.add(cppConfig.getCppExecutable().getPathString());

    // When using gold to link an executable, output the number of used and unused symbols.
    if (symbolCountsOutput != null) {
      argv.add("-Wl,--print-symbol-counts=" + symbolCountsOutput.getExecPathString());
    }

    if (linkTargetType == LinkTargetType.DYNAMIC_LIBRARY) {
      argv.add("-shared");
    }

    // Add the outputs of any associated linkstamp compilations.
    for (Artifact linkstampOutput : linkstamps.values()) {
      argv.add(linkstampOutput.getExecPathString());
    }

    boolean fullyStatic = (linkStaticness == LinkStaticness.FULLY_STATIC);
    boolean mostlyStatic = (linkStaticness == LinkStaticness.MOSTLY_STATIC);
    boolean sharedLinkopts =
        linkTargetType == LinkTargetType.DYNAMIC_LIBRARY
        || linkopts.contains("-shared")
        || cppConfig.getLinkOptions().contains("-shared");
    boolean needWholeArchive = Link.needWholeArchive(linkStaticness, linkTargetType, linkopts,
        nativeDeps, cppConfig);

    if (output != null) {
      argv.add("-o");
      String execpath = output.getExecPathString();
      if (mostlyStatic
          && linkTargetType == LinkTargetType.EXECUTABLE
          && cppConfig.skipStaticOutputs()) {
        // Linked binary goes to /dev/null; bogus dependency info in its place.
        Collections.addAll(argv, "/dev/null", "-MMD", "-MF", execpath);  // thanks Ambrose
      } else {
        argv.add(execpath);
      }
    }

    addInputFileLinkOptions(argv, needWholeArchive, /*includeLinkopts=*/true);

    // Extra toolchain link options based on the output's link staticness.
    if (fullyStatic) {
      argv.addAll(cppConfig.getFullyStaticLinkOptions(features, sharedLinkopts));
    } else if (mostlyStatic) {
      argv.addAll(cppConfig.getMostlyStaticLinkOptions(features, sharedLinkopts));
    } else {
      argv.addAll(cppConfig.getDynamicLinkOptions(features, sharedLinkopts));
    }

    if (configuration.isCodeCoverageEnabled()) {
      // Note we apply the same logic independently in GoCompilationHelper (using "--coverage").
      // Keep this in mind if this ever gets moved out to CROSSTOOL or a centralized place
      // for both languages becomes available.
      argv.add("-lgcov");
    }

    if (linkTargetType == LinkTargetType.EXECUTABLE && cppConfig.forcePic()) {
      argv.add("-pie");
    }

    argv.addAll(cppConfig.getLinkOptions());
    argv.addAll(cppConfig.getFdoSupport().getLinkOptions());
  }

  private static boolean isDynamicLibrary(LinkerInput linkInput) {
    Artifact libraryArtifact = linkInput.getArtifact();
    String name = libraryArtifact.getFilename();
    return Link.SHARED_LIBRARY_FILETYPES.matches(name) && name.startsWith("lib");
  }

  private boolean isSharedNativeLibrary() {
    return nativeDeps && configuration.getFragment(CppConfiguration.class).shareNativeDeps();
  }

  /**
   * When linking a shared library fully or mostly static then we need to link in
   * *all* dependent files, not just what the shared library needs for its own
   * code. This is done by wrapping all objects/libraries with
   * -Wl,-whole-archive and -Wl,-no-whole-archive. For this case the
   * globalNeedWholeArchive parameter must be set to true.  Otherwise only
   * library objects (.lo) need to be wrapped with -Wl,-whole-archive and
   * -Wl,-no-whole-archive.
   */
  private void addInputFileLinkOptions(List<String> argv, boolean globalNeedWholeArchive,
      boolean includeLinkopts) {
    // The Apple ld doesn't support -whole-archive/-no-whole-archive. It
    // does have -all_load/-noall_load, but -all_load is a global setting
    // that affects all subsequent files, and -noall_load is simply ignored.
    // TODO(bazel-team): Not sure what the implications of this are, other than
    // bloated binaries.
    CppConfiguration cppConfig = configuration.getFragment(CppConfiguration.class);
    boolean macosx = cppConfig.getTargetLibc().equals("macosx");
    if (globalNeedWholeArchive) {
      argv.add(macosx ? "-Wl,-all_load" : "-Wl,-whole-archive");
    }

    // Used to collect -L and -Wl,-rpath options, ensuring that each used only once.
    Set<String> libOpts = new LinkedHashSet<>();

    // List of command line parameters to link input files (either directly or using -l).
    List<String> linkerInputs = new ArrayList<>();

    // List of command line parameters that need to be placed *outside* of
    // --whole-archive ... --no-whole-archive.
    List<String> noWholeArchiveInputs = new ArrayList<>();

    PathFragment solibDir = configuration.getBinDirectory().getExecPath()
        .getRelative(cppConfig.getSolibDirectory());
    String runtimeSolibName = runtimeSolibDir != null ? runtimeSolibDir.getBaseName() : null;
    boolean runtimeRpath = runtimeSolibDir != null
        && (linkTargetType == LinkTargetType.DYNAMIC_LIBRARY
        || (linkTargetType == LinkTargetType.EXECUTABLE
        && linkStaticness == LinkStaticness.DYNAMIC));

    String rpathRoot = null;

    if (output != null) {
      String origin = useExecOrigin ? "$EXEC_ORIGIN/" : "$ORIGIN/";
      rpathRoot = "-Wl,-rpath," + origin;
      if (runtimeRpath) {
        argv.add("-Wl,-rpath," + origin + runtimeSolibName + "/");
      }

      // Calculate the correct relative value for the "-rpath" link option (which sets
      // the search path for finding shared libraries).
      if (isSharedNativeLibrary()) {
        // For shared native libraries, special symlinking is applied to ensure C++
        // runtimes are available under $ORIGIN/_solib_[arch]. So we set the RPATH to find
        // them.
        //
        // Note that we have to do this because $ORIGIN points to different paths for
        // different targets. In other words, blaze-bin/p1/p2/p3/a_shareddeps.so and
        // blaze-bin/p4/b_shareddeps.so have different path depths. The first could
        // reference a standard blaze-bin/_solib_[arch] via $ORIGIN/../../../_solib[arch],
        // and the second could use $ORIGIN/../_solib_[arch]. But since this is a shared
        // artifact, both are symlinks to the same place, so
        // there's no *one* RPATH setting that fits all targets involved in the sharing.
        rpathRoot += ":" + origin + cppConfig.getSolibDirectory() + "/";
        if (runtimeRpath) {
          argv.add("-Wl,-rpath," + origin + "../" + runtimeSolibName + "/");
        }
      } else {
        // For all other links, calculate the relative path from the output file to _solib_[arch]
        // (the directory where all shared libraries are stored, which resides under the blaze-bin
        // directory. In other words, given blaze-bin/my/package/binary, rpathRoot would be
        // "../../_solib_[arch]".
        if (runtimeRpath) {
          argv.add("-Wl,-rpath," + origin
              + Strings.repeat("../", output.getRootRelativePath().segmentCount() - 1)
              + runtimeSolibName + "/");
        }

        rpathRoot += Strings.repeat("../", output.getRootRelativePath().segmentCount() - 1)
            + cppConfig.getSolibDirectory() + "/";

        if (nativeDeps) {
          // We also retain the $ORIGIN/ path to solibs that are in _solib_<arch>, as opposed to
          // the package directory)
          if (runtimeRpath) {
            argv.add("-Wl,-rpath," + origin + "../" + runtimeSolibName + "/");
          }
          rpathRoot += ":" + origin;
        }
      }
    }

    boolean includeSolibDir = false;
    for (LinkerInput input : getLinkerInputs()) {
      if (isDynamicLibrary(input)) {
        PathFragment libDir = input.getArtifact().getExecPath().getParentDirectory();
        Preconditions.checkState(
            libDir.startsWith(solibDir),
            "Artifact '%s' is not under directory '%s'.", input.getArtifact(), solibDir);
        if (libDir.equals(solibDir)) {
          includeSolibDir = true;
        }
        addDynamicInputLinkOptions(input, linkerInputs, libOpts, solibDir, rpathRoot);
      } else {
        addStaticInputLinkOptions(input, linkerInputs);
      }
    }

    for (LinkerInput input : runtimeInputs) {
      List<String> optionsList = globalNeedWholeArchive
          ? noWholeArchiveInputs
          : linkerInputs;

      if (isDynamicLibrary(input)) {
        PathFragment libDir = input.getArtifact().getExecPath().getParentDirectory();
        Preconditions.checkState(
            libDir.startsWith(solibDir)
            || (runtimeSolibDir != null && libDir.equals(runtimeSolibDir)),
            "Artifact '%s' is not under directory '%s'.", input.getArtifact(), solibDir);
        if (libDir.equals(solibDir)
            || (runtimeSolibDir != null && libDir.equals(runtimeSolibDir))) {
          includeSolibDir = true;
        }
        addDynamicInputLinkOptions(input, optionsList, libOpts, solibDir, rpathRoot);
      } else {
        addStaticInputLinkOptions(input, optionsList);
      }
    }

    if (includeSolibDir && rpathRoot != null) {
      argv.add(rpathRoot);
    }
    argv.addAll(libOpts);

    // Need to wrap static libraries with whole-archive option
    for (String option : linkerInputs) {
      if (!globalNeedWholeArchive && Link.LINK_LIBRARY_FILETYPES.matches(option)) {
        argv.add(macosx ? "-Wl,-all_load" : "-Wl,-whole-archive");
        argv.add(option);
        argv.add(macosx ? "-Wl,-noall_load" : "-Wl,-no-whole-archive");
      } else {
        argv.add(option);
      }
    }

    if (globalNeedWholeArchive) {
      argv.add(macosx ? "-Wl,-noall_load" : "-Wl,-no-whole-archive");
      argv.addAll(noWholeArchiveInputs);
    }

    if (includeLinkopts) {
      /*
       * For backwards compatibility, linkopts come _after_ inputFiles.
       * This is needed to allow linkopts to contain libraries and
       * positional library-related options such as
       *    -Wl,--begin-group -lfoo -lbar -Wl,--end-group
       * or
       *    -Wl,--as-needed -lfoo -Wl,--no-as-needed
       *
       * As for the relative order of the three different flavours of linkopts
       * (global defaults, per-target linkopts, and command-line linkopts),
       * we have no idea what the right order should be, or if anyone cares.
       */
      argv.addAll(linkopts);
    }
  }

  /**
   * Adds command-line options for a dynamic library input file into
   * options and libOpts.
   */
  private void addDynamicInputLinkOptions(LinkerInput input, List<String> options,
      Set<String> libOpts, PathFragment solibDir, String rpathRoot) {
    Preconditions.checkState(isDynamicLibrary(input));
    Preconditions.checkState(
        !Link.useStartEndLib(input, CppHelper.archiveType(configuration)));

    Artifact inputArtifact = input.getArtifact();
    PathFragment libDir = inputArtifact.getExecPath().getParentDirectory();
    if (rpathRoot != null
        && !libDir.equals(solibDir)
        && (runtimeSolibDir == null || !runtimeSolibDir.equals(libDir))) {
      String dotdots = "";
      PathFragment commonParent = solibDir;
      while (!libDir.startsWith(commonParent)) {
        dotdots += "../";
        commonParent = commonParent.getParentDirectory();
      }

      libOpts.add(rpathRoot + dotdots + libDir.relativeTo(commonParent).getPathString());
    }

    libOpts.add("-L" + inputArtifact.getExecPath().getParentDirectory().getPathString());

    String name = inputArtifact.getFilename();
    if (CppFileTypes.SHARED_LIBRARY.matches(name)) {
      String libName = name.replaceAll("(^lib|\\.so$)", "");
      options.add("-l" + libName);
    } else {
      // Interface shared objects have a non-standard extension
      // that the linker won't be able to find.  So use the
      // filename directly rather than a -l option.  Since the
      // library has an SONAME attribute, this will work fine.
      options.add(inputArtifact.getExecPathString());
    }
  }

  /**
   * Adds command-line options for a static library or non-library input
   * into options.
   */
  private void addStaticInputLinkOptions(LinkerInput input, List<String> options) {
    Preconditions.checkState(!isDynamicLibrary(input));

    // start-lib/end-lib library: adds its input object files.
    if (Link.useStartEndLib(
        input, configuration.getFragment(CppConfiguration.class).archiveType())) {
      Iterable<Artifact> archiveMembers = input.getObjectFiles();
      if (!Iterables.isEmpty(archiveMembers)) {
        options.add("-Wl,--start-lib");
        for (Artifact member : archiveMembers) {
          options.add(member.getExecPathString());
        }
        options.add("-Wl,--end-lib");
      }
    // For anything else, add the input directly.
    } else {
      Artifact inputArtifact = input.getArtifact();
      if (input.isFake()) {
        options.add(Link.FAKE_OBJECT_PREFIX + inputArtifact.getExecPathString());
      } else {
        options.add(inputArtifact.getExecPathString());
      }
    }
  }

  /**
   * A builder for a {@link LinkCommandLine}.
   */
  public static final class Builder {
    private BuildConfiguration configuration;
    private ActionOwner owner;
    @Nullable private Artifact output;
    @Nullable private Artifact interfaceOutput;
    @Nullable private Artifact symbolCountsOutput;
    private ImmutableList<Artifact> buildInfoHeaderArtifacts = ImmutableList.of();
    private Iterable<? extends LinkerInput> linkerInputs = ImmutableList.of();
    private Iterable<? extends LinkerInput> runtimeInputs = ImmutableList.of();
    @Nullable private LinkTargetType linkTargetType;
    private LinkStaticness linkStaticness = LinkStaticness.FULLY_STATIC;
    private ImmutableList<String> linkopts = ImmutableList.of();
    private ImmutableSet<String> features = ImmutableSet.of();
    private ImmutableMap<Artifact, Artifact> linkstamps = ImmutableMap.of();
    @Nullable private PathFragment runtimeSolibDir;
    private boolean nativeDeps;
    private boolean useExecOrigin;
    @Nullable private Artifact interfaceSoBuilder;

    public Builder(BuildConfiguration configuration, ActionOwner owner) {
      this.configuration = configuration;
      this.owner = owner;
    }

    public Builder(RuleContext ruleContext) {
      this(ruleContext.getConfiguration(), ruleContext.getActionOwner());
    }

    public LinkCommandLine build() {
      return new LinkCommandLine(configuration, owner, output, interfaceOutput,
          symbolCountsOutput, buildInfoHeaderArtifacts, linkerInputs, runtimeInputs, linkTargetType,
          linkStaticness, linkopts, features, linkstamps, runtimeSolibDir, nativeDeps,
          useExecOrigin, interfaceSoBuilder);
    }

    /**
     * Sets the type of the link. It is an error to try to set this to {@link
     * LinkTargetType#INTERFACE_DYNAMIC_LIBRARY}. Note that all the static target types (see {@link
     * LinkTargetType#isStaticLibraryLink}) are equivalent, and there is no check that the output
     * artifact matches the target type extension.
     */
    public Builder setLinkTargetType(LinkTargetType linkTargetType) {
      Preconditions.checkArgument(linkTargetType != LinkTargetType.INTERFACE_DYNAMIC_LIBRARY);
      this.linkTargetType = linkTargetType;
      return this;
    }

    /**
     * Sets the primary output artifact. This must be called before calling {@link #build}.
     */
    public Builder setOutput(Artifact output) {
      this.output = output;
      return this;
    }

    /**
     * Sets a list of linker inputs. These get turned into linker options depending on the
     * staticness and the target type.
     */
    public Builder setLinkerInputs(Iterable<LinkerInput> linkerInputs) {
      this.linkerInputs = CollectionUtils.makeImmutable(linkerInputs);
      return this;
    }

    public Builder setRuntimeInputs(Iterable<LinkerInput> runtimeInputs) {
      this.runtimeInputs = CollectionUtils.makeImmutable(runtimeInputs);
      return this;
    }

    /**
     * Sets the additional interface output artifact, which is only used for dynamic libraries. The
     * {@link #build} method throws an exception if the target type is not {@link
     * LinkTargetType#DYNAMIC_LIBRARY}.
     */
    public Builder setInterfaceOutput(Artifact interfaceOutput) {
      this.interfaceOutput = interfaceOutput;
      return this;
    }

    /**
     * Sets an additional output artifact that contains symbol counts. The {@link #build} method
     * throws an exception if this is non-null for a static link (see
     * {@link LinkTargetType#isStaticLibraryLink}).
     */
    public Builder setSymbolCountsOutput(Artifact symbolCountsOutput) {
      this.symbolCountsOutput = symbolCountsOutput;
      return this;
    }

    /**
     * Sets the linker options. These are passed to the linker in addition to the other linker
     * options like linker inputs, symbol count options, etc. The {@link #build} method
     * throws an exception if the linker options are non-empty for a static link (see {@link
     * LinkTargetType#isStaticLibraryLink}).
     */
    public Builder setLinkopts(ImmutableList<String> linkopts) {
      this.linkopts = linkopts;
      return this;
    }

    /**
     * Sets how static the link is supposed to be. For static target types (see {@link
     * LinkTargetType#isStaticLibraryLink}), the {@link #build} method throws an exception if this
     * is not {@link LinkStaticness#FULLY_STATIC}. The default setting is {@link
     * LinkStaticness#FULLY_STATIC}.
     */
    public Builder setLinkStaticness(LinkStaticness linkStaticness) {
      this.linkStaticness = linkStaticness;
      return this;
    }

    /**
     * Sets the binary that should be used to create the interface output for a dynamic library.
     * This is ignored unless the target type is {@link LinkTargetType#DYNAMIC_LIBRARY} and an
     * interface output artifact is specified.
     */
    public Builder setInterfaceSoBuilder(Artifact interfaceSoBuilder) {
      this.interfaceSoBuilder = interfaceSoBuilder;
      return this;
    }

    /**
     * Sets the linkstamps. Linkstamps are additional C++ source files that are compiled as part of
     * the link command. The {@link #build} method throws an exception if the linkstamps are
     * non-empty for a static link (see {@link LinkTargetType#isStaticLibraryLink}).
     */
    public Builder setLinkstamps(ImmutableMap<Artifact, Artifact> linkstamps) {
      this.linkstamps = linkstamps;
      return this;
    }

    /**
     * The build info header artifacts are generated header files that are used for link stamping.
     * The {@link #build} method throws an exception if the build info header artifacts are
     * non-empty for a static link (see {@link LinkTargetType#isStaticLibraryLink}).
     */
    public Builder setBuildInfoHeaderArtifacts(ImmutableList<Artifact> buildInfoHeaderArtifacts) {
      this.buildInfoHeaderArtifacts = buildInfoHeaderArtifacts;
      return this;
    }

    /**
     * Sets the features enabled for the rule.
     */
    public Builder setFeatures(ImmutableSet<String> features) {
      this.features = features;
      return this;
    }

    /**
     * Sets the directory of the dynamic runtime libraries, which is added to the rpath. The {@link
     * #build} method throws an exception if the runtime dir is non-null for a static link (see
     * {@link LinkTargetType#isStaticLibraryLink}).
     */
    public Builder setRuntimeSolibDir(PathFragment runtimeSolibDir) {
      this.runtimeSolibDir = runtimeSolibDir;
      return this;
    }

    /**
     * Whether the resulting library is intended to be used as a native library from another
     * programming language. This influences the rpath. The {@link #build} method throws an
     * exception if this is true for a static link (see {@link LinkTargetType#isStaticLibraryLink}).
     */
    public Builder setNativeDeps(boolean nativeDeps) {
      this.nativeDeps = nativeDeps;
      return this;
    }

    /**
     * Sets whether to use {@code $EXEC_ORIGIN} instead of {@code $ORIGIN} in the rpath. This
     * requires a dynamic linker that support this feature. The {@link #build} method throws an
     * exception if this is true for a static link (see {@link LinkTargetType#isStaticLibraryLink}).
     */
    public Builder setUseExecOrigin(boolean useExecOrigin) {
      this.useExecOrigin = useExecOrigin;
      return this;
    }
  }
}