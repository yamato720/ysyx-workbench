package npc

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import scala.jdk.CollectionConverters._

class PackageOwnershipTest extends AnyFlatSpec {
  private val packagePattern = raw"(?m)^package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$$".r

  private def belongsTo(pkg: String, owner: String): Boolean =
    pkg == owner || pkg.startsWith(s"$owner.")

  private def scalaFiles(root: Path): Vector[Path] = {
    val paths = Files.walk(root)
    try paths.iterator.asScala
      .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
      .toVector
    finally paths.close()
  }

  private def packageName(path: Path): String = {
    val source = ConfigCatalogGenerator.codeOnly(Files.readString(path, StandardCharsets.UTF_8))
    packagePattern.findFirstMatchIn(source).map(_.group(1)).getOrElse(
      throw new IllegalArgumentException(s"Scala 源文件缺少 package：$path"))
  }

  "Scala package ownership" should "keep all active SPMV sources in accelerators.spmv" in {
    val root = ConfigCatalogGenerator.locateNpcRoot(Paths.get(".").toAbsolutePath.normalize).get
    val sourceRoots = Vector(
      root.resolve("chisel/accelerators/spmv/scala"),
      root.resolve("chisel/accelerators/spmv/test"),
      root.resolve("chisel/configs/accelerators/spmv"),
      root.resolve("chisel/configs/fpga/u55c/spmv"),
      root.resolve("fpga/common/scala/accelerators/spmv")
    )
    val mismatches = sourceRoots.flatMap(scalaFiles).filterNot { path =>
      belongsTo(packageName(path), "accelerators.spmv")
    }
    assert(mismatches.isEmpty, s"SPMV 源码 package 越界：${mismatches.mkString(", ")}")
  }

  it should "keep common accelerator sources independent of product namespaces" in {
    val root = ConfigCatalogGenerator.locateNpcRoot(Paths.get(".").toAbsolutePath.normalize).get
    val commonRoots = Vector(
      root.resolve("chisel/accelerators/common/scala"),
      root.resolve("chisel/accelerators/common/test")
    )
    val packageMismatches = commonRoots.flatMap(scalaFiles).filterNot { path =>
      belongsTo(packageName(path), "accelerators.common")
    }
    assert(packageMismatches.isEmpty,
      s"公共 accelerator 源码 package 越界：${packageMismatches.mkString(", ")}")

    val productNamespace = raw"\baccelerators\.spmv(?:\.|\b)".r
    val leaked = commonRoots.flatMap(scalaFiles).filter { path =>
      val code = ConfigCatalogGenerator.codeOnly(Files.readString(path, StandardCharsets.UTF_8))
      productNamespace.findFirstIn(code).nonEmpty
    }
    assert(leaked.isEmpty, s"公共 accelerator 层反向依赖 SPMV：${leaked.mkString(", ")}")
  }

  it should "keep the shared AXI4 contract in ip-interface" in {
    val root = ConfigCatalogGenerator.locateNpcRoot(Paths.get(".").toAbsolutePath.normalize).get
    val contract = root.resolve("chisel/ip-interface/scala/Axi4Contracts.scala")
    assert(Files.isRegularFile(contract), s"缺少公共 AXI4 契约：$contract")
    assert(packageName(contract) == "npc.ip.axi")

    val forbiddenRoots = Vector(
      root.resolve("chisel/rv-core/scala"),
      root.resolve("chisel/accelerators")
    )
    val duplicateDeclaration = raw"\b(?:class|object)\s+Axi4(?:Address|ReadData|WriteData|WriteResponse|ReadMasterIO|WriteMasterIO|ReadWriteMasterIO)\b".r
    val duplicates = forbiddenRoots.flatMap(scalaFiles).filter { path =>
      val code = ConfigCatalogGenerator.codeOnly(Files.readString(path, StandardCharsets.UTF_8))
      duplicateDeclaration.findFirstIn(code).nonEmpty
    }
    assert(duplicates.isEmpty, s"公共 AXI4 契约被重复定义：${duplicates.mkString(", ")}")
  }

  it should "keep first-party Chisel source roots compact" in {
    val root = ConfigCatalogGenerator.locateNpcRoot(Paths.get(".").toAbsolutePath.normalize).get
    val requiredRoots = Vector(
      root.resolve("chisel/rv-core/scala"),
      root.resolve("chisel/rv-core/test"),
      root.resolve("chisel/accelerators/common/scala"),
      root.resolve("chisel/accelerators/common/test"),
      root.resolve("chisel/accelerators/spmv/scala"),
      root.resolve("chisel/accelerators/spmv/test")
    )
    val missing = requiredRoots.filterNot(Files.isDirectory(_))
    assert(missing.isEmpty, s"缺少紧凑源码根：${missing.mkString(", ")}")

    val legacyRoots = Vector(
      root.resolve("chisel/rv-core/main"),
      root.resolve("chisel/rv-core/test/scala"),
      root.resolve("chisel/accelerators/common/main"),
      root.resolve("chisel/accelerators/common/test/scala"),
      root.resolve("chisel/accelerators/spmv/main"),
      root.resolve("chisel/accelerators/spmv/test/scala")
    )
    val redundant = legacyRoots.filter(Files.exists(_))
    assert(redundant.isEmpty, s"重新出现冗余源码目录：${redundant.mkString(", ")}")
  }

  it should "forbid legacy SPMV packages and declarations in the generic Config layer" in {
    val root = ConfigCatalogGenerator.locateNpcRoot(Paths.get(".").toAbsolutePath.normalize).get
    val activeRoots = Vector(
      root.resolve("chisel/accelerators"),
      root.resolve("chisel/configs"),
      root.resolve("fpga/common/scala"),
      root.resolve("fpga/u55c/scala"),
      root.resolve("fpga/zcu102/scala")
    )
    val legacy = activeRoots.flatMap(scalaFiles).filter { path =>
      val pkg = packageName(path)
      Vector("spmv", "npc.spmv", "accelerator.spmv").exists(belongsTo(pkg, _))
    }
    assert(legacy.isEmpty, s"发现旧 SPMV package：${legacy.mkString(", ")}")

    val genericConfigRoots = Vector(
      root.resolve("chisel/configs/common"),
      root.resolve("chisel/configs/npc")
    )
    val leaked = genericConfigRoots.flatMap(scalaFiles).filter { path =>
      val code = ConfigCatalogGenerator.codeOnly(Files.readString(path, StandardCharsets.UTF_8))
      raw"\bSpmv[A-Za-z0-9_]*\b".r.findFirstIn(code).nonEmpty
    }
    assert(leaked.isEmpty, s"通用 Config 层含 SPMV 专属声明：${leaked.mkString(", ")}")
  }

  it should "keep the shared FPGA layer independent of product FPGA namespaces" in {
    val root = ConfigCatalogGenerator.locateNpcRoot(Paths.get(".").toAbsolutePath.normalize).get
    val sharedRoots = Vector(
      root.resolve("chisel/configs/fpga/base"),
      root.resolve("fpga/common/scala/fpga")
    )
    val productNamespace = raw"\b(?:npc\.fpga|ysyx\.fpga|accelerators\.spmv)(?:\.|\b)".r
    val leaked = sharedRoots.flatMap(scalaFiles).filter { path =>
      val code = ConfigCatalogGenerator.codeOnly(Files.readString(path, StandardCharsets.UTF_8))
      productNamespace.findFirstIn(code).nonEmpty
    }
    assert(leaked.isEmpty, s"共享 FPGA 层反向依赖产品 namespace：${leaked.mkString(", ")}")
  }
}
