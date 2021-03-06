package com.atomist.source.file

import java.io.{File, FileInputStream}
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.{FileSystems, Files, Paths}

import com.atomist.source._
import com.atomist.source.file.ClassPathArtifactSource.{classPathResourceToFile, toArtifactSource}
import com.atomist.source.filter.{AtomistIgnoreFileFilter, GitDirFilter, GitignoreFileFilter}
import com.atomist.util.BinaryDecider.isBinaryContent
import org.scalatest._

import scala.collection.JavaConverters._

object FileSystemArtifactSourceTest {

  val AtomistTemplatesSource = toArtifactSource("spring-boot")

  val PosixSupported = FileSystems.getDefault.getFileStores.asScala
    .exists(_.supportsFileAttributeView(classOf[PosixFileAttributeView]))

  def ignoreFiles1ZipId = {
    val f = classPathResourceToFile("ignore-files/no-dot-git.zip")
    ZipFileInput(new FileInputStream(f))
  }

  def ignoreFiles2ZipId = {
    val f = classPathResourceToFile("ignore-files/dot-git-negated-node_modules.zip")
    ZipFileInput(new FileInputStream(f))
  }

  def ignoreFiles3ZipId = {
    val f = classPathResourceToFile("ignore-files/dot-atomist-ignored-node_modules.zip")
    ZipFileInput(new FileInputStream(f))
  }
}

class FileSystemArtifactSourceTest extends FlatSpec with Matchers {

  import FileSystemArtifactSourceTest._

  val fWriter = new FileSystemArtifactSourceWriter

  it should "handle classpath directory not found" in {
    an[ArtifactSourceException] should be thrownBy toArtifactSource("this is complete nonsense")
  }

  it should "find single file and verify contents" in {
    val classpathSource = toArtifactSource("java-source/HelloWorldService.java")
    val artifacts = classpathSource.artifacts
    val files = artifacts.filter(_.isInstanceOf[FileArtifact])
    artifacts.size should be > 0
    val aFile = files.head.asInstanceOf[FileArtifact]
    aFile.contentLength should be > 0
    aFile.content should have size aFile.contentLength
    isBinaryContent(aFile.content) shouldBe false
    if (PosixSupported)
      aFile.mode should be(FileArtifact.DefaultMode)
  }

  it should "find single image file" in {
    val classpathSource = toArtifactSource("spring-boot/web-template/src/main/resources/atomist-logo-horiz.png")
    val artifacts = classpathSource.artifacts
    val files = artifacts.filter(_.isInstanceOf[FileArtifact])
    artifacts.size should be > 0
    val aFile = files.head.asInstanceOf[FileArtifact]
    aFile.contentLength should be > 0
    isBinaryContent(aFile.content) shouldBe true
  }

  it should "find single binary file" in {
    val classpathSource = toArtifactSource("binary.dat")
    val artifacts = classpathSource.artifacts
    val files = artifacts.filter(_.isInstanceOf[FileArtifact])
    artifacts.size should be > 0
    val aFile = files.head.asInstanceOf[FileArtifact]
    aFile.contentLength should be > 0
    isBinaryContent(aFile.content) shouldBe true
  }

  it should "find single binary executable file" in {
    val classpathSource = toArtifactSource("binary-executable.dat")
    val artifacts = classpathSource.artifacts
    val files = artifacts.filter(_.isInstanceOf[FileArtifact])
    artifacts.size should be > 0
    val aFile = files.head.asInstanceOf[FileArtifact]
    aFile.contentLength should be > 0
    isBinaryContent(aFile.content) shouldBe true
    if (PosixSupported)
      aFile.mode should be(FileArtifact.ExecutableMode)
  }

  it should "find directory" in {
    val artifacts = AtomistTemplatesSource.artifacts
    artifacts.exists(_.name contains "web-template") shouldBe true
  }

  it should "find empty directory" in {
    val artifacts = AtomistTemplatesSource.artifacts
    artifacts.exists(_.name contains "empty-dir") shouldBe true
  }

  it should "find all files via flatten" in {
    validateTargetDirectory(AtomistTemplatesSource)
  }

  // TODO some of these tests are more generic ArtifactSource tests
  it should "be able to cache" in {
    val classpathSource = AtomistTemplatesSource
    classpathSource.allFiles.exists(_.isCached) shouldBe false
    validateTargetDirectory(classpathSource)
    val cachedCopy = classpathSource.cached
    cachedCopy.allFiles.exists(!_.isCached) shouldBe false
    validateTargetDirectory(cachedCopy)
  }

  it should "be able to filter files" in {
    val s = AtomistTemplatesSource / "atomistTemplates"
    val files = s.allFiles
    files.exists(_.name contains ".vm") shouldBe true
    val filtered = s.filter(_ => true, !_.name.contains(".vm"))
    filtered.allFiles.exists(_.name contains ".vm") shouldBe false
    withClue("should leave nothing after filter") {
      filtered.allFiles shouldBe empty
    }
  }

  it should "be able to filter directories" in {
    val s = AtomistTemplatesSource
    s.allFiles.exists(_.name contains "Application") shouldBe true
    val filtered = s.filter(!_.name.contains("spring"), f => true)
    filtered.allFiles.exists(_.name contains "Java") shouldBe false
  }

  it should "be able to find existing directory" in {
    val s = AtomistTemplatesSource
    s.directories.nonEmpty shouldBe true
    s.findDirectory("atomistTemplates") shouldBe defined
  }

  it should "not be able to find bogus directory" in {
    val s = AtomistTemplatesSource
    s.directories.nonEmpty shouldBe true
    s.findDirectory("xsdfsdfsdfsdf") shouldBe empty
  }

  it should "reject bogus file rootPath" in {
    val f: File = new File("/this/is/not/a/real.rootPath")
    val fsid = FileSystemArtifactSourceIdentifier(f)
    an[ArtifactSourceException] should be thrownBy new FileSystemArtifactSource(fsid)
  }

  it should "handle filtering source with no .gitignore" in {
    val zid = ignoreFiles1ZipId
    val zipSource = ZipFileArtifactSourceReader.fromZipSource(zid)

    val tmpDir = Files.createTempDirectory(null)
    val fid = FileSystemArtifactSourceIdentifier(tmpDir.toFile)
    fWriter.write(zipSource, fid, SimpleSourceUpdateInfo(getClass.getName))

    val as = FileSystemArtifactSource(fid)
    as.findDirectory(".atomist/node_modules") shouldBe defined
  }

  it should "handle filtering source with negated 'node_modules' in .gitignore" in {
    val zid = ignoreFiles2ZipId
    val zipSource = ZipFileArtifactSourceReader.fromZipSource(zid)

    val tmpDir = Files.createTempDirectory(null)
    val fid = FileSystemArtifactSourceIdentifier(tmpDir.toFile)
    fWriter.write(zipSource, fid, SimpleSourceUpdateInfo(getClass.getName))

    val as = FileSystemArtifactSource(fid, GitignoreFileFilter(tmpDir.toString))
    as.findDirectory(".atomist/node_modules") shouldBe defined
  }

  it should "handle filtering source with 'node_modules' in .atomist/ignore" in {
    val zid = ignoreFiles3ZipId
    val zipSource = ZipFileArtifactSourceReader.fromZipSource(zid)

    val tmpDir = Files.createTempDirectory(null)
    val fid = FileSystemArtifactSourceIdentifier(tmpDir.toFile)
    fWriter.write(zipSource, fid, SimpleSourceUpdateInfo(getClass.getName))

    val as = FileSystemArtifactSource(fid,
      GitignoreFileFilter(tmpDir.toString),
      AtomistIgnoreFileFilter(tmpDir.toString))
    as.findDirectory(".atomist/node_modules") shouldBe empty
    as.findDirectory("target") shouldBe empty
  }

  it should "handle filtering .git and target from artifact-source" in {
    val rootPath = System.getProperty("user.dir")
    val fid = FileSystemArtifactSourceIdentifier(Paths.get(rootPath).toFile)
    // val start = System.currentTimeMillis()
    val as = FileSystemArtifactSource(fid,
      GitignoreFileFilter(rootPath),
      AtomistIgnoreFileFilter(rootPath),
      GitDirFilter(rootPath))
    as.findDirectory("src") shouldBe defined
    as.findDirectory(".git") shouldBe empty
    as.findDirectory("target") shouldBe empty
    // println(s"elapsed time = ${System.currentTimeMillis() - start} ms")
  }

  private def validateTargetDirectory(s: ArtifactSource): Unit =
    s.allFiles.exists(_.name contains ".vm")
}
