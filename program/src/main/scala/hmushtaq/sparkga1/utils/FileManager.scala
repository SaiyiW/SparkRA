/*
 * Copyright (C) 2017-2018 TU Delft, The Netherlands
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Authors: Hamid Mushtaq, Saiyi Wang
 *
 */
package hmushtaq.sparkga1.utils

import java.io._
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.FileUtils


/**
 *
 * @author Hamid Mushtaq, Saiyi Wang
 */
object FileManager
{
	def unzipGZ(gzFilePath: String , destFilePath: String) {
        val gzipInputStream = new GZIPInputStream(new FileInputStream(gzFilePath));
        val out = new FileOutputStream(destFilePath);
        val buf = new Array[Byte](64*1024); //64 kB
        var len = gzipInputStream.read(buf)
        while (len > 0){
            out.write(buf, 0, len);
            len = gzipInputStream.read(buf)
        }
        gzipInputStream.close();
        out.close();
    }

	def getFileLock(fChannel: FileChannel, logName:String, x:String, config:Configuration):FileLock = {
        var lock = fChannel.tryLock()
        var i = 0
        while(lock==null){
            if(i%5==0)
                LogWriter.dbgLog("star/" + logName, "*\tThread "+ x + ": "+"Have waited for lock for " + i*5 +"s.", config)
			Thread.sleep(1000)
			lock = fChannel.tryLock()
            i+=1
		}
        return lock
	}
	def getBAMFileSize(filePath: String , config: Configuration) :Long ={
		val hdfsManager = new HDFSManager

		if(config.getMode != "local")
		    return hdfsManager.getFileSize(filePath)
		else {
			val file = new File(filePath)
			return file.length
		}
	}

	def getInputFileNames(dir: String, config: Configuration) : Array[String] = 
	{
		val mode = config.getMode
		val hdfsManager = new HDFSManager
		
		if (mode != "local")
		{
			val a: Array[String] = hdfsManager.getFileList(dir)

			return a
		}
		else
		{
			var d = new File(dir)	
			
			if (d.exists && d.isDirectory) 
			{
				val list: List[File] = d.listFiles.filter(_.isFile).toList
				val a: Array[String] = new Array[String](list.size)
				var i = 0
				
				for(i <- 0 until list.size)
					a(i) = list(i).getName
				
				return a
			} 
			else
				return null
		}
	}
	
	def exists(filePath: String, config: Configuration) : Boolean =
	{
		if (config.getMode == "local")
			return new File(filePath).exists
		else
		{
			val hdfsManager = new HDFSManager
			return hdfsManager.exists(filePath)
		}
	}

	def getFileNameFromPath(path: String) : String =
	{
		return path.substring(path.lastIndexOf('/') + 1)
	}
	
	def getDirFromPath(path: String) : String =
	{
	    if (!path.contains('/'))
	        return "./"
		return path.substring(0, path.lastIndexOf('/') + 1)
	}

	def getRefFilePath(config: Configuration) : String = 
	{
		return if (config.getMode() == "local") config.getRefPath() else 
			config.getSfFolder() + getFileNameFromPath(config.getRefPath())
	}

    //saiyi
	def getRefFileDire(config: Configuration) : String = 
	{
		var refDire = ""
		if (config.getMode() == "local") 
		    {
				refDire = config.getRefPath().substring(0, config.getRefPath().lastIndexOf('/') + 1) 
			}
		else 
			refDire = config.getSfFolder()
		return refDire
	}
	def getStarIndexLocalDire(passNo:String, config:Configuration) : String = {
        var indexDir = ""
		if (config.getMode() == "local") {
			if(passNo=="1")
			    indexDir = config.getStarRefFolder() 
			else
			    indexDir = config.getTmpFolder()+"STAR_index"+passNo+"/"
		}
		else{ //cluster mode
		    if(config.getSTAR1onLocal&&(passNo=="1")){
				indexDir =config.getSTAR1LocalPath
			}
			else{
                indexDir = config.getStarLocalFolder()+"STAR_index"+passNo+"/"
			}
		}
		
		return indexDir
	}

	def getSnpFilePath(config: Configuration) : String = 
	{
		return if (config.getMode() == "local") config.getSnpPath() else
			config.getSfFolder() + config.getSnpPath()
	}

	def getIndelFilePath(config: Configuration) : String = 
	{
		return if (config.getMode() == "local") config.getIndelPath() else
			config.getSfFolder() + getFileNameFromPath(config.getIndelPath())
	}

	def getExomeFilePath(config: Configuration) : String = 
	{
		return if (config.getMode() == "local") config.getExomePath() else
			config.getSfFolder() + getFileNameFromPath(config.getExomePath())
	}

	def getToolsDirPath(config: Configuration) : String = 
	{
		return if (config.getMode() == "local") config.getToolsFolder() else "./"
	}

	private def getTimeStamp() : String =
	{
		return new SimpleDateFormat("HH:mm:ss").format(Calendar.getInstance().getTime())
	}

	def readWholeFile(fname: String, config: Configuration) : String =
	{
		val hdfsManager = new HDFSManager
		
		if (config.getMode != "local")
			return hdfsManager.readWholeFile(fname)
		else
			return scala.io.Source.fromFile(fname).mkString
	}

	def readPartialFile(fname: String, bytes: Int, config: Configuration) : String =
	{
		val hdfsManager = new HDFSManager
		
		if (config.getMode != "local")
			return hdfsManager.readPartialFile(fname, bytes)
		else
			return scala.io.Source.fromFile(fname).mkString
	}

	def writeWholeFile(fname: String, s: String, config: Configuration)
	{
		val hdfsManager = new HDFSManager
		
		if (config.getMode != "local")
			hdfsManager.writeWholeFile(fname, s)
		else
			new PrintWriter(fname) {write(s); close}
	}
	
	def uploadFileToOutput(filePath: String, outputPath: String, delSrc: Boolean, config: Configuration)
	{
		if (config.getMode() != "local")
		{
			val fileName = getFileNameFromPath(filePath)
			new File(config.getTmpFolder + "." + fileName + ".crc").delete()
			val hdfsManager = new HDFSManager
			
			hdfsManager.upload(fileName, config.getTmpFolder, config.getOutputFolder + outputPath + "/", delSrc) 
		}
	}

	def makeDirIfRequired(dir: String, config: Configuration)
	{
		if (config.getMode == "local")
		{
			val file = new File(dir)
			if (!file.exists())
				file.mkdir()
		}			
	}

	def cleanAndMakeLocalDir(dir:String, config: Configuration){
        val file = new File(dir)
		if (!file.exists())
			file.mkdir()
		else if(file.exists()){
			FileUtils.deleteDirectory(file)
			file.mkdir()
		}
	}

	def downloadBWAFiles(x: String, config: Configuration)
	{
		val refFolder = getDirFromPath(config.getRefPath())
		val refFileName = getFileNameFromPath(config.getRefPath())
		val hdfsManager = new HDFSManager
		
		if (!(new File(config.getSfFolder).exists))
			new File(config.getSfFolder()).mkdirs()
		
		hdfsManager.downloadIfRequired(refFileName, refFolder, config.getSfFolder);
		hdfsManager.downloadIfRequired(refFileName.replace(".fasta", ".dict"), refFolder, config.getSfFolder)
		hdfsManager.downloadIfRequired(refFileName + ".amb", refFolder, config.getSfFolder)
		hdfsManager.downloadIfRequired(refFileName + ".ann", refFolder, config.getSfFolder)
		hdfsManager.downloadIfRequired(refFileName + ".bwt", refFolder, config.getSfFolder);
		hdfsManager.downloadIfRequired(refFileName + ".fai", refFolder, config.getSfFolder)
		hdfsManager.downloadIfRequired(refFileName + ".pac", refFolder, config.getSfFolder)
		hdfsManager.downloadIfRequired(refFileName + ".sa", refFolder, config.getSfFolder)
	}

	def downloadRNARef(config: Configuration) {

		val refFolder = getDirFromPath(config.getRefPath())             //hdfs path
		val refFileName = getFileNameFromPath(config.getRefPath())
		val hdfsManager = new HDFSManager

		if (!(new File(config.getSfFolder).exists)) {
			new File(config.getSfFolder()).mkdirs()
		}
		
		hdfsManager.downloadIfRequired(refFileName, refFolder, config.getSfFolder);   //.fasta
		hdfsManager.downloadIfRequired(refFileName.replace(".fasta", ".dict"), refFolder, config.getSfFolder)  //.dict
		hdfsManager.downloadIfRequired(refFileName + ".fai", refFolder, config.getSfFolder)    //.fai

	}

	def downloadSTARIndex(passNo:String, config:Configuration) :String = {
		if(config.getSTAR1onLocal&&(passNo=="1"))
		    return ""
		else{
		var indexFolderHDFS = config.getStarRefFolder()                            //hdfs path 
		val hdfsManager = new HDFSManager
		val localIndexFolder = config.getStarLocalFolder()+"STAR_index"+passNo+"/" //local path

		if(passNo=="2"){
			indexFolderHDFS = config.getOutputFolder() + "STAR_index"+passNo+"/"   //hdfs path of new index
		}

		if (!(new File(localIndexFolder).exists)) {
			new File(localIndexFolder).mkdirs()
		}

		hdfsManager.downloadIfRequired("chrLength.txt",        indexFolderHDFS, localIndexFolder)
		hdfsManager.downloadIfRequired("chrName.txt",          indexFolderHDFS, localIndexFolder)
		hdfsManager.downloadIfRequired("chrNameLength.txt",    indexFolderHDFS, localIndexFolder)
		hdfsManager.downloadIfRequired("chrStart.txt",         indexFolderHDFS, localIndexFolder)
		hdfsManager.downloadIfRequired("Genome",               indexFolderHDFS, localIndexFolder)
		hdfsManager.downloadIfRequired("genomeParameters.txt", indexFolderHDFS, localIndexFolder)
        hdfsManager.downloadIfRequired("SA",                   indexFolderHDFS, localIndexFolder)
		hdfsManager.downloadIfRequired("SAindex",              indexFolderHDFS, localIndexFolder)
		if(passNo == "2"){
			hdfsManager.downloadIfRequired("sjdbInfo.txt",     indexFolderHDFS, localIndexFolder)
			hdfsManager.downloadIfRequired("sjdbList.out.tab", indexFolderHDFS, localIndexFolder)
		}

		return localIndexFolder
       }
	}

	def deleteSTARIndex(passNo:String, config : Configuration) {
		
			if(passNo=="1" && ( (config.getMode()=="local")||(config.getSTAR1onLocal()) ))
			    return
			else{
				val d = new File(getStarIndexLocalDire(passNo, config))
				this.synchronized{
                    FileUtils.deleteDirectory(d)
				}
			}
	}

	def downloadVCFRefFiles(x: String, config: Configuration)
	{
		val refFolder = getDirFromPath(config.getRefPath())
		val refFileName = getFileNameFromPath(config.getRefPath())
		val hdfsManager = new HDFSManager
		
		if (!(new File(config.getSfFolder).exists))
			new File(config.getSfFolder()).mkdirs()
		
		hdfsManager.downloadIfRequired(refFileName, refFolder, config.getSfFolder);
		hdfsManager.downloadIfRequired(refFileName.replace(".fasta", ".dict"), refFolder, config.getSfFolder)
		hdfsManager.downloadIfRequired(refFileName + ".fai", refFolder, config.getSfFolder)
		
		val snpFolder = getDirFromPath(config.getSnpPath)
		val snpFileName = getFileNameFromPath(config.getSnpPath)
		hdfsManager.downloadIfRequired(snpFileName, snpFolder, config.getSfFolder)
		
		if (config.useKnownIndels)
		{
			val indelFolder = getDirFromPath(config.getIndelPath())
			val indelFileName = getFileNameFromPath(config.getIndelPath())
			hdfsManager.downloadIfRequired(indelFileName, indelFolder, config.getSfFolder())
		}
	}

	def downloadVCFIndexFiles(x: String, config: Configuration)
	{
		val hdfsManager = new HDFSManager
		
		val snpFolder = getDirFromPath(config.getSnpPath)
		val snpFileName = getFileNameFromPath(config.getSnpPath)
		hdfsManager.downloadIfRequired(snpFileName + ".idx", snpFolder, config.getSfFolder)
		
		if (config.useKnownIndels)
		{
			val indelFolder = getDirFromPath(config.getIndelPath())
			val indelFileName = getFileNameFromPath(config.getIndelPath())
			hdfsManager.downloadIfRequired(indelFileName + ".idx", indelFolder, config.getSfFolder)
		}	
	}
}