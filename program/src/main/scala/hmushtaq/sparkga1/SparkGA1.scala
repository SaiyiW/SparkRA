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
package hmushtaq.sparkga1

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import sys.process._
import org.apache.spark.scheduler._

import java.io._
import java.nio.file.{Paths, Files}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.net._

import scala.sys.process.Process
import scala.io.Source
import scala.collection.JavaConversions._
import scala.collection.mutable._
import scala.util.Sorting._
import scala.concurrent.Future
import scala.concurrent.forkjoin._
import scala.util.Random

import utils._

import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.spark.storage.StorageLevel._
import org.apache.spark.HashPartitioner

import htsjdk.samtools.util.BufferedLineReader
import htsjdk.samtools._

/**
 *
 * @author Hamid Mushtaq, Saiyi Wang
 */
object SparkGA1
{
	final val saveAllStages = false
	final val downloadSAMFileInLB = true //Saiyi: original  true; local: false; Changed to true on sara
	final val writeToHDFSDirectlyInLB = true
	// Scheduling
	final val sizeBasedLBScheduling = true
	final val sizeBasedVCScheduling = true
	//////////////////////////////////////////////////////////////////////////////
	def deleteStarLocalContent(direFromLastStep:String, x:String, config:Configuration):Int={ //arguments dire not used: to adapt to case
		val hdfsManager = new HDFSManager
		val dire = if (config.getMode != "local") {config.getStarLocalFolder()+"starGenomeLock/"} else {config.getTmpFolder+"starGenomeLock/"} //in case starLocalFolder = ./
		val d = new File(dire)
		var logName = ""
		 if (config.getMode != "local"){
			 val IP = InetAddress.getLocalHost().toString()
			 val node = IP.substring(0, IP.indexOf('/'))
			 logName=node+"_deleteLock.log"  //node+"_t"+ x+"_deleteLock.log"
			 this.synchronized{hdfsManager.createIfRequired(config.getOutputFolder + "log/star/" + logName)}
		 }
		 else{
			 logName="deleteLock.log"
		     FileManager.makeDirIfRequired(config.getOutputFolder + "log/star", config)
		 }
		
		
			
		this.synchronized{
			if ((config.getMode!="local") && (dire == "/tmp/")){
			    LogWriter.dbgLog("star/" + logName, "Thread "+ x + ": "+"Will not delete the /tmp folder! Exiting..", config)
                return -1
		    }
            if (d.exists && d.isDirectory) 
		    {
			    FileUtils.deleteDirectory(d)
				FileManager.deleteSTARIndex("2", config)
			    LogWriter.dbgLog("star/" + logName, "Thread "+ x + ": "+"Directory " + dire + " deleted!", config)

		    } 
		    else{
			    LogWriter.dbgLog("star/" + logName, "Thread "+ x + ": "+"Directory " + dire + " doesn't exist!", config)

		    }
		}
		return 0
		
	}
	
	def starGenomeLoad(passNo: String, genomeLoad: String, x:String, config: Configuration):String = {
		
		val hdfsManager = new HDFSManager
        var fileName = ""
		val genomeDire= FileManager.getStarIndexLocalDire(passNo, config)
		var genomeLockDirectory = ""
		var logName = ""
		var testdownloadTime = 0.0

		 if (config.getMode != "local"){
			 val IP = InetAddress.getLocalHost().toString()
			 val node = IP.substring(0, IP.indexOf('/'))
			 logName=node+"_" +genomeLoad+passNo+".log"   //node+"_t"+ x + "_" +genomeLoad+passNo+".log"
			this.synchronized{hdfsManager.createIfRequired(config.getOutputFolder + "log/star/" + logName)}
            genomeLockDirectory = config.getStarLocalFolder()+"starGenomeLock/"
            if(genomeLoad != "Remove"){
				val t0 = System.currentTimeMillis
                FileManager.downloadSTARIndex(passNo, config)
				testdownloadTime = (System.currentTimeMillis - t0) / 1000
				this.synchronized{LogWriter.dbgLog("star/" + logName, "*\tThread "+ x + ": "+"Downloading index time = "+ testdownloadTime + " seconds.", config)}
			}
			    

			if (!(new File(genomeLockDirectory).exists))
				    new File(genomeLockDirectory).mkdirs()
		    }
		 else{
			    logName=genomeLoad+passNo+".log"
			    FileManager.makeDirIfRequired(config.getOutputFolder + "log/star", config)
			    genomeLockDirectory = config.getTmpFolder+"starGenomeLock/"
			    if(!(new File(genomeLockDirectory).exists)){
                    new File(genomeLockDirectory).mkdirs()
			    }
			}
        //e.g. lock1Load.txt
		
		if(genomeLoad == "LoadAndKeep" || genomeLoad == "LoadAndExit" || genomeLoad == "LoadAndRemove" ){
             fileName = "lock" + passNo + "Load.txt"
		}
		else //Remove
		{
			fileName = "lock" + passNo + "Remove.txt"
		}
		    

		val lockFile = new File(genomeLockDirectory+fileName)
		if(!lockFile.exists) lockFile.createNewFile()
		val randAcFile = new RandomAccessFile(lockFile, "rw")
		val fChannel = randAcFile.getChannel()
		var lock:FileLock = null
		val nthreads = java.lang.Runtime.getRuntime.availableProcessors

		/////////////////////////////mutex part/////////////////////////////////////////
		this.synchronized{
            try{
            lock = FileManager.getFileLock(fChannel, logName, x, config)
		    val byteBuffer = ByteBuffer.allocate(2)

		   //job has been done
		    if(fChannel.read(byteBuffer)>0)  {
               LogWriter.dbgLog("star/" + logName, "*\tThread "+ x + ": "+"The genome job: "+ genomeLoad + " in pass "+passNo+" has been done. Skipping.", config)
		    }
		    else{
			    LogWriter.dbgLog("star/" + logName, "*\tThread "+ x + ": "+"Starting the genome job: "+ genomeLoad + " in pass "+passNo+".", config)
                val progName = FileManager.getToolsDirPath(config) + "STAR "
		        val tmpDir = config.getTmpFolder
			    val outFilePrefix = tmpDir+logName+"_"
		
			    val command_str = progName + "--genomeDir "  + genomeDire + " --outFileNamePrefix " + outFilePrefix + " --genomeLoad " + genomeLoad + " --runThreadN " + nthreads

	            LogWriter.dbgLog("star/" + logName, "*\tThread "+ x + ": "+"Starting the genome job: "+ genomeLoad + " in pass "+passNo+" -> " + command_str, config)
		
			    var cmd_res = command_str.!

			    byteBuffer.putChar('y').flip()
			    fChannel.write(byteBuffer)
			    fChannel.force(false)
				LogWriter.dbgLog("star/" + logName, "*\tThread "+ x + ": "+"Finished the genome job: "+ genomeLoad + " in pass "+passNo+".", config)
				new File(outFilePrefix+"Aligned.out.sam").delete() 
		        new File(outFilePrefix+"Log.out").delete()
		        new File(outFilePrefix+"Log.progress.out").delete()
				/*test saiyi*/
				if(genomeLoad == "Remove"){
					val cmd2 = "ipcs -m"
			        val stdErrorSb1 = new StringBuilder
					val stdOutSb1 = new StringBuilder
					val logger1 = ProcessLogger(
						(o: String) => {stdOutSb1.append(o + '\n')},
						(e: String) => {stdErrorSb1.append(e + '\n')}
		    		)
					LogWriter.dbgLog("star/" + logName, "*\tStarting the ipcs job: -> " + cmd2, config)
		    		cmd2 ! logger1
            
					LogWriter.dbgLog("star/" + logName, "*\tThread "+ x +": Finished ipcs. \nstdout: " + stdOutSb1.toString + "\n stderr: \n" + stdErrorSb1.toString, config)
				}
		    }
		}finally{
            if(lock!=null) lock.release()

		    randAcFile.close()
		}
		}
		
		
        return genomeLockDirectory
        
        ////////////////////////////////////////////////////////////////////////////////
	}

	def starRun(x: String, config: Configuration) : (Array[((Integer, Integer, Integer), String)]) = 
	{
		val blockSize = 4096 * 1024; 
		var input_file = ""
		var input_file2= ""
		val tmpDir = config.getTmpFolder
		val inDir = config.getInputFolder
		val outDir = config.getOutputFolder
		val mode = config.getMode
		val hdfsManager = new HDFSManager
		//val downloadRef = config.getSfFolder == "./"
		val logName = x + ".fq.log"
		val paired = config.getPaired()
		var res1 = ArrayBuffer.empty[((Integer, Integer, Integer), String)]
		var testdownloadTime = 0.0

		if (mode != "local")
		{
			hdfsManager.create(outDir + "log/star/" + logName)
			
			if (!(new File(tmpDir).exists))
				new File(tmpDir).mkdirs()
			
		/* 	if (downloadRef && (mode != "local"))
			{
				LogWriter.dbgLog("star/" + logName, "*\tDownloading reference if required.", config)
				val t0 = System.currentTimeMillis
				FileManager.downloadRNARef(config)
				testdownloadTime = (System.currentTimeMillis - t0) / 1000
				LogWriter.dbgLog("star/" + logName, "*\tThread "+ x + ": "+"Downloading ref time = "+ testdownloadTime + " seconds.", config)

			} */
		}
		else
		{ //local mode
			if(paired) {
				input_file = inDir + x + "-1.fq.gz"; 
				input_file2 = inDir + x + "-2.fq.gz"
				}
			else
			    input_file = inDir + x + ".fq.gz" //x is just a number
			FileManager.makeDirIfRequired(outDir + "log/star", config)
		}

		val file = new File(FileManager.getToolsDirPath(config) + "STAR")
		file.setExecutable(true)

        //saiyi: haven't changed streaming to paired case
		if (config.doStreamingBWA)
		{
			val chunkNum = x.split('.')(0)
			while(!FileManager.exists(inDir + "ulStatus/" + chunkNum, config))
			{
				if (FileManager.exists(inDir + "ulStatus/end.txt", config))
				{
					if (!FileManager.exists(inDir + "ulStatus/" + chunkNum, config))
					{
						LogWriter.dbgLog("star/" + x, "#\tchunkNum = " + chunkNum + ", end.txt exists but this file doesn't!. Returning an empty array.", config)
						return res1.toArray
					}
				}
				Thread.sleep(1000)
			}
		}

		if (mode != "local")
		{
			LogWriter.dbgLog("star/" + logName, "0a\tDownloading from the HDFS", config)
			if(paired){
				hdfsManager.download(x + "-1.fq.gz", inDir, tmpDir, false)
				hdfsManager.download(x + "-2.fq.gz", inDir, tmpDir, false)
				input_file = tmpDir + x + "-1.fq.gz"
				input_file2= tmpDir + x + "-2.fq.gz"
			}
			else{
				hdfsManager.download(x + ".fq.gz", inDir, tmpDir, false)
			    input_file = tmpDir + x + ".fq.gz"
			}
		
		}

		// unzip the input .gz file
		var fqFileName = tmpDir + input_file.substring(input_file.lastIndexOf('/') + 1).replace(".gz", "") //e.g. tmpDir+"0-1.fq", dest file
		LogWriter.dbgLog("star/" + logName, "0b\t" + "unzipping input file: " + input_file, config)
		FileManager.unzipGZ(input_file, fqFileName)

		if(paired){
			fqFileName = tmpDir + input_file2.substring(input_file2.lastIndexOf('/') + 1).replace(".gz", "")
		    LogWriter.dbgLog("star/" + logName, "0b\t" + "unzipping input file: " + input_file2, config)
		    FileManager.unzipGZ(input_file2, fqFileName)
			fqFileName = tmpDir + input_file.substring(input_file.lastIndexOf('/') + 1).replace(".gz", "") + " " + tmpDir + input_file2.substring(input_file2.lastIndexOf('/') + 1).replace(".gz", "")
		}
	
		if (mode != "local"){
            new File(input_file).delete()
			if(paired) new File(input_file2).delete()
		}
			
		// run STAR align pass 1
		val progName = FileManager.getToolsDirPath(config) + "STAR "
		val outFilePrefix = tmpDir + x +"_"
		val nthreads = config.getNumThreads.toInt
		val bufferSize = "30000000"
		val genomeLoad = "LoadAndKeep"
		// Example: STAR --genomeDir STAR_ref --runThreadN 8 --readFilesIn x.fq --outFileNamePrefix tmpDir/star1out/x_  (e.g. x_SJ.out.tab)
		val command_str = progName + "--genomeDir "  + FileManager.getStarIndexLocalDire("1", config) + " --runThreadN " + nthreads.toString + 
			" --readFilesIn " + fqFileName + " --outFileNamePrefix " + outFilePrefix + " --limitIObufferSize " + bufferSize + " --genomeLoad " + genomeLoad + " --outSAMtype None" 
			//" --alignSJDBoverhangMin 8" //default : 3
		LogWriter.dbgLog("star/" + logName, "1\tstar pass 1 alignment started, RGID = " + config.getRGID + " -> " + command_str, config)
		
		var cmd_res = command_str.!
		
		
		
		var fileName = outFilePrefix + "SJ.out.tab"

		if (!Files.exists(Paths.get(fileName))){
			LogWriter.dbgLog("star/" + logName, "File: " + fileName + " does not exist, returning an empty array", config)
            return res1.toArray
		}

		for (line <- Source.fromFile(fileName).getLines()) 
		{
			val e = line.split('\t')
			val chr = e(0)
			val start = e(1).toInt
			val end = e(2).toInt
			val chrNumber = config.getChrIndex(chr)
					
			res1.append(((chrNumber, start, end), line))
		}

		// Delete useless files
		//new File(outFilePrefix+"Aligned.out.sam").delete() //added  --outSAMtype None only in pass 1 alignment
		new File(outFilePrefix+"Log.final.out").delete()
		new File(outFilePrefix+"Log.out").delete()
		new File(outFilePrefix+"Log.progress.out").delete()
		new File(outFilePrefix+"SJ.out.tab").delete()
        for(e <- fqFileName.split(' '))
		    new File(e).delete()
		

		return res1.toArray

	}

	def starRun2(x: String, config: Configuration) : (Array[((Integer, Integer), (String, Long, Int, Int, String))]) = {

		var input_file = ""
		var input_file2= ""
		val tmpDir = config.getTmpFolder
		val inDir = config.getInputFolder
		val hdfsManager = new HDFSManager
		val blockSize = 4096 * 1024; 
		val logName = x + ".fq.log"
		val paired = config.getPaired()
		//val downloadRef = config.getSfFolder == "./"
		var testdownloadTime = 0.0

		LogWriter.dbgLog("star/" + logName, "0a\tStarting star pass2 alignment", config)

		if (config.getMode != "local")
		{
			/* if(downloadRef){
				LogWriter.dbgLog("star/" + logName, "*\tDownloading reference if required.", config)
				val t0 = System.currentTimeMillis
				FileManager.downloadRNARef(config)
				testdownloadTime = (System.currentTimeMillis - t0) / 1000
				LogWriter.dbgLog("star/" + logName, "*\tThread "+ x + ": "+"Downloading ref time = "+ testdownloadTime + " seconds.", config)

			} */
			LogWriter.dbgLog("star/" + logName, "0a\tDownloading input again from the HDFS", config)
			if(paired){
				hdfsManager.download(x + "-1.fq.gz", inDir, tmpDir, false)
				hdfsManager.download(x + "-2.fq.gz", inDir, tmpDir, false)
				input_file = tmpDir + x + "-1.fq.gz"
				input_file2= tmpDir + x + "-2.fq.gz"
			}
			else{
				hdfsManager.download(x + ".fq.gz", inDir, tmpDir, false)
			    input_file = tmpDir + x + ".fq.gz"
			}
		}
		else
		{
			if(paired) {
				input_file = inDir + x + "-1.fq.gz"; 
				input_file2 = inDir + x + "-2.fq.gz"
				}
			else
			    input_file = inDir + x + ".fq.gz" //x is just a number
		}

		// unzip the input .gz file
		var fqFileName = tmpDir + input_file.substring(input_file.lastIndexOf('/') + 1).replace(".gz", "") //e.g. tmpDir+"0-1.fq"
		LogWriter.dbgLog("star/" + logName, "0b\t" + "unzipping input file: " + input_file, config)
		FileManager.unzipGZ(input_file, fqFileName)

		if(paired){
			fqFileName = tmpDir + input_file2.substring(input_file2.lastIndexOf('/') + 1).replace(".gz", "")
		    LogWriter.dbgLog("star/" + logName, "0b\t" + "unzipping input file: " + input_file2, config)
		    FileManager.unzipGZ(input_file2, fqFileName)
			fqFileName = tmpDir + input_file.substring(input_file.lastIndexOf('/') + 1).replace(".gz", "") + " " + tmpDir + input_file2.substring(input_file2.lastIndexOf('/') + 1).replace(".gz", "")
		}
	
		if (config.getMode != "local"){
            new File(input_file).delete()
			if(paired) new File(input_file2).delete()
		}
		

		// run STAR align pass 2
		val progName = FileManager.getToolsDirPath(config) + "STAR "
		val outFilePrefix = tmpDir + x +"_p2_"   //e.g. 9_p2_Aligned.out.sam
		val nthreads = config.getNumThreads.toInt
		val bufferSize = "30000000"
		val genomeLoad = "LoadAndKeep"

		val command_str = progName + "--genomeDir "  + FileManager.getStarIndexLocalDire("2", config) + " --runThreadN " + nthreads.toString + 
			" --readFilesIn " + fqFileName + " --outFileNamePrefix " + outFilePrefix + " --limitIObufferSize " + bufferSize + " --genomeLoad " + genomeLoad + " --outStd SAM"
		LogWriter.dbgLog("star/" + logName, "1\tstar pass 2 alignment started, RGID = " + config.getRGID + " -> " + command_str, config)

		var writerMap = new HashMap[(Integer, Integer), SamRegion]()
		val samRegionsParser = new SamRegionsParser(x+".fq", writerMap, config)
		val stdErrorSb = new StringBuilder
		val logger = ProcessLogger(
			(o: String) => {
				samRegionsParser.append(o)
				},
			(e: String) => {stdErrorSb.append(e + '\n')}
		)
		command_str ! logger

		// Delete useless files
		new File(outFilePrefix+"SJ.out.tab").delete()
		new File(outFilePrefix+"Log.final.out").delete()
		new File(outFilePrefix+"Log.out").delete()
		new File(outFilePrefix+"Log.progress.out").delete()
		new File(outFilePrefix+"Log.std.out").delete()
		for(e <- fqFileName.split(' '))
		    new File(e).delete()
		//no sam out file
		//new File(outFilePrefix+"Aligned.out.sam").delete()

		FileManager.makeDirIfRequired(config.getOutputFolder + "samChunks", config)
		FileManager.makeDirIfRequired(config.getOutputFolder + "starPos", config)

		LogWriter.dbgLog("star/" + logName, "2\tUploading SAM Files to the HDFS. reads = " + samRegionsParser.getNumOfReads + ", bad lines = " + samRegionsParser.getBadLines, config)
		var currentNum = 1
		var outStr = new StringBuilder
		var dbgStr = new StringBuilder
		var totalBytes = 0
		// For positions
		var posCurrentNum = 1
		var posOutStr = new StringBuilder
		var posTotalBytes = 0
		val iWriterMap = writerMap.toMap // Might help with Garbage collection of the mutable HashMap.
		val res = ArrayBuffer.empty[((Integer, Integer), (String, Long, Int, Int, String))]
		writerMap = null
		////////////////
		for((k,samRegion) <- iWriterMap)
		{
			val t1 = System.currentTimeMillis
			val chr = k._1
			val reg = k._2
			
			val minPos = samRegion.getMinPos
			val maxPos = samRegion.getMaxPos
			val content = samRegion.getContent
			val posContent = samRegion.getPositionsStr
			var posInfoStr = ""
			
			// For positions /////////////////////////////////////////////////////
			if ((posTotalBytes + posContent.size) > blockSize)
			{
				FileManager.writeWholeFile(config.getOutputFolder + "starPos/pos_" + x + "-" + posCurrentNum, posOutStr.toString, config)
				posCurrentNum += 1
				posOutStr.setLength(0)
				posTotalBytes = 0
				posInfoStr = "pos_" + x + "-" + posCurrentNum + ",0," + posContent.size
			}
			else
			{
				posInfoStr = "pos_" + x + "-" + posCurrentNum + "," + posTotalBytes + "," + (posTotalBytes + posContent.size)
			}
			posOutStr.append(posContent)
			posTotalBytes += posContent.size
			//////////////////////////////////////////////////////////////////////
			if ((totalBytes + content.size) > 0)
			{
				//dbgStr.append("+writeWholeFile\t")
				FileManager.writeWholeFile(config.getOutputFolder + "samChunks/chunk_" + x + "-" + currentNum, outStr.toString, config)
				currentNum += 1
				outStr.setLength(0)
				totalBytes = 0
				res.append(((chr, reg), ("chunk_" + x + "-" + currentNum + ",0," + content.size, samRegion.getSize, minPos, maxPos, posInfoStr)))
			}
			else
			{
				//dbgStr.append("-noWritingToWholeFile\t")
				res.append(((chr, reg), ("chunk_" + x + "-" + currentNum + "," + totalBytes + "," + (totalBytes + content.size), samRegion.getSize, 
					minPos, maxPos, posInfoStr)))
			}
			outStr.append(content)
			totalBytes += content.size
			//dbgStr.append((System.currentTimeMillis - t1).toString)
		}
		val t1 = System.currentTimeMillis
		FileManager.writeWholeFile(config.getOutputFolder + "samChunks/chunk_" + x + "-" + currentNum, outStr.toString, config)
		FileManager.writeWholeFile(config.getOutputFolder + "starPos/pos_" + x + "-" + posCurrentNum, posOutStr.toString, config)
		dbgStr.append("\n" + (System.currentTimeMillis - t1).toString)
		//LogWriter.dbgLog("bwa/" + x, t0, "*\tTime taken by each loop iteration for chunk making step = " + dbgStr, config)
		LogWriter.dbgLog("star/" + logName, "3\tSAM files uploaded to the HDFS. # of positions files = " + posCurrentNum + ", # of sam files = " + 
			currentNum + "\n=============================================================================\n" + stdErrorSb.toString + 
			"\n=============================================================================\n", config)

		return res.toArray

	}

	def bwaRun(x: String, config: Configuration) : (Array[((Integer, Integer), (String, Long, Int, Int, String))]) = 
	{
		val blockSize = 4096 * 1024; 
		var input_file = ""
		val tmpDir = config.getTmpFolder
		val hdfsManager = new HDFSManager
		val downloadRef = config.getSfFolder == "./"
		
		if (config.getMode != "local")
		{
			hdfsManager.create(config.getOutputFolder + "log/bwa/" + x)
			
			if (!(new File(config.getTmpFolder).exists))
				new File(config.getTmpFolder).mkdirs()
			
			if (downloadRef && (config.getMode != "local"))
			{
				LogWriter.dbgLog("bwa/" + x, "*\tDownloading reference files for bwa if required.", config)
				FileManager.downloadBWAFiles("bwa/" + x, config)
			}
		}
		else
		{
			input_file = config.getInputFolder + x + ".gz"
			FileManager.makeDirIfRequired(config.getOutputFolder + "log/bwa", config)
		}
		
		val file = new File(FileManager.getToolsDirPath(config) + "bwa") 
		file.setExecutable(true)
		val res = ArrayBuffer.empty[((Integer, Integer), (String, Long, Int, Int, String))]
		
		if (config.doStreamingBWA)
		{
			val chunkNum = x.split('.')(0)
			while(!FileManager.exists(config.getInputFolder + "ulStatus/" + chunkNum, config))
			{
				if (FileManager.exists(config.getInputFolder + "ulStatus/end.txt", config))
				{
					if (!FileManager.exists(config.getInputFolder + "ulStatus/" + chunkNum, config))
					{
						LogWriter.dbgLog("bwa/" + x, "#\tchunkNum = " + chunkNum + ", end.txt exists but this file doesn't!", config)
						res.append(((-1, -1), ("", 0, 0, 0, "")))
						return res.toArray
					}
				}
				Thread.sleep(1000)
			}
		}
	
		if (config.getMode != "local")
		{
			LogWriter.dbgLog("bwa/" + x, "0a\tDownloading from the HDFS", config)
			hdfsManager.download(x + ".gz", config.getInputFolder, tmpDir, false)
			input_file = tmpDir + x + ".gz"
		}
		
		// unzip the input .gz file
		var fqFileName = tmpDir + x
		val unzipStr = "gunzip -c " + input_file
		LogWriter.dbgLog("bwa/" + x, "0b\t" + unzipStr, config)
		unzipStr #> new java.io.File(fqFileName) !;
		if (config.getMode != "local")
			new File(input_file).delete()
		
		// run bwa mem
		val progName = FileManager.getToolsDirPath(config) + "bwa mem "
		val outFileName = tmpDir + "out_" + x
		val nthreads = config.getNumThreads.toInt
		// Example: bwa mem input_files_directory/fasta_file.fasta -p -t 2 x.fq > out_file
		val command_str = progName + FileManager.getRefFilePath(config) + " " + config.getExtraBWAParams + " -t " + nthreads.toString + " " + fqFileName
		LogWriter.dbgLog("bwa/" + x, "1\tbwa mem started, RGID = " + config.getRGID + " -> " + command_str, config)
		var writerMap = new HashMap[(Integer, Integer), SamRegion]()
		val samRegionsParser = new SamRegionsParser(x, writerMap, config)
		val stdErrorSb = new StringBuilder
		val logger = ProcessLogger(
			(o: String) => {
				samRegionsParser.append(o)
				},
			(e: String) => {stdErrorSb.append(e + '\n')}
		)
		command_str ! logger;
		new File(fqFileName).delete()
		
		FileManager.makeDirIfRequired(config.getOutputFolder + "samChunks", config)
		FileManager.makeDirIfRequired(config.getOutputFolder + "bwaPos", config)
		
		LogWriter.dbgLog("bwa/" + x, "2\tUploading SAM Files to the HDFS. reads = " + samRegionsParser.getNumOfReads + ", bad lines = " + samRegionsParser.getBadLines, config)
		var currentNum = 1
		var outStr = new StringBuilder
		var dbgStr = new StringBuilder
		var totalBytes = 0
		// For positions
		var posCurrentNum = 1
		var posOutStr = new StringBuilder
		var posTotalBytes = 0
		val iWriterMap = writerMap.toMap // Might help with Garbage collection of the mutable HashMap.
		writerMap = null
		////////////////
		for((k,samRegion) <- iWriterMap)
		{
			val t1 = System.currentTimeMillis
			val chr = k._1
			val reg = k._2
			
			val minPos = samRegion.getMinPos
			val maxPos = samRegion.getMaxPos
			val content = samRegion.getContent
			val posContent = samRegion.getPositionsStr
			var posInfoStr = ""
			
			// For positions /////////////////////////////////////////////////////
			if ((posTotalBytes + posContent.size) > blockSize)
			{
				FileManager.writeWholeFile(config.getOutputFolder + "bwaPos/pos_" + x + "-" + posCurrentNum, posOutStr.toString, config)
				posCurrentNum += 1
				posOutStr.setLength(0)
				posTotalBytes = 0
				posInfoStr = "pos_" + x + "-" + posCurrentNum + ",0," + posContent.size
			}
			else
			{
				posInfoStr = "pos_" + x + "-" + posCurrentNum + "," + posTotalBytes + "," + (posTotalBytes + posContent.size)
			}
			posOutStr.append(posContent)
			posTotalBytes += posContent.size
			//////////////////////////////////////////////////////////////////////
			if ((totalBytes + content.size) > 0)
			{
				//dbgStr.append("+writeWholeFile\t")
				FileManager.writeWholeFile(config.getOutputFolder + "samChunks/chunk_" + x + "-" + currentNum, outStr.toString, config)
				currentNum += 1
				outStr.setLength(0)
				totalBytes = 0
				res.append(((chr, reg), ("chunk_" + x + "-" + currentNum + ",0," + content.size, samRegion.getSize, minPos, maxPos, posInfoStr)))
			}
			else
			{
				//dbgStr.append("-noWritingToWholeFile\t")
				res.append(((chr, reg), ("chunk_" + x + "-" + currentNum + "," + totalBytes + "," + (totalBytes + content.size), samRegion.getSize, 
					minPos, maxPos, posInfoStr)))
			}
			outStr.append(content)
			totalBytes += content.size
			//dbgStr.append((System.currentTimeMillis - t1).toString)
		}
		val t1 = System.currentTimeMillis
		FileManager.writeWholeFile(config.getOutputFolder + "samChunks/chunk_" + x + "-" + currentNum, outStr.toString, config)
		FileManager.writeWholeFile(config.getOutputFolder + "bwaPos/pos_" + x + "-" + posCurrentNum, posOutStr.toString, config)
		dbgStr.append("\n" + (System.currentTimeMillis - t1).toString)
		//LogWriter.dbgLog("bwa/" + x, t0, "*\tTime taken by each loop iteration for chunk making step = " + dbgStr, config)
		LogWriter.dbgLog("bwa/" + x, "3\tSAM files uploaded to the HDFS. # of positions files = " + posCurrentNum + ", # of sam files = " + 
			currentNum + "\n=============================================================================\n" + stdErrorSb.toString, config)

		return res.toArray
	}

	def getSamFileHeader(samFile: String) : String = 
	{
		val br = new BufferedReader(new FileReader(samFile))
		val sb = new StringBuilder
		var line = br.readLine()

		while ((line != null) && (line(0) == '@')) 
		{
			sb.append(line);
			sb.append(System.lineSeparator());
			line = br.readLine()
		}
		
		br.close()
		return sb.toString()
	}
		
	def makeBAMFiles(chrRegion: (Integer, Integer), files: Array[(String, Long, Int, Int, String)], avgReadsPerRegion: Long, config: Configuration) : 
		((Integer, Integer), Int) = 
	{
		val chr = chrRegion._1
		val reg = chrRegion._2
		val reads = files.map(x => x._2).reduce(_+_)
		var segments = (reads.toFloat * config.getRegionsFactor.toFloat / avgReadsPerRegion).round.toInt
		var retSegments = 1
		val minPos = files.map(x => x._3).reduceLeft(_ min _)
		val maxPos = files.map(x => x._4).reduceLeft(_ max _)
		val posRange = maxPos - minPos
		// Number of threads
		val nThreads = config.getNumThreads.toInt
		val hdfsManager = new HDFSManager
		
		if (config.getMode != "local")
		{
			if (segments > 1)
				hdfsManager.create(config.getOutputFolder + "log/lb2/" + chr + "_" + reg)
			else
				hdfsManager.create(config.getOutputFolder + "log/lb/" + chr + "_" + reg)
		}
		else
		{
			FileManager.makeDirIfRequired(config.getOutputFolder + "log/lb", config)
			FileManager.makeDirIfRequired(config.getOutputFolder + "log/lb2", config)
		}
		
		if ((config.getMode != "local") && !(new File(config.getTmpFolder).exists))
			new File(config.getTmpFolder).mkdirs()
		val absPath = new File(config.getTmpFolder).getAbsolutePath(); //test saiyi
		if (segments > 1)
		{
			LogWriter.dbgLog("lb2/" + chr + "_" + reg, "!!!Absolute path of the tmpdir: " + absPath, config);
			LogWriter.dbgLog("lb2/" + chr + "_" + reg, "1a\tNumber of reads = " + reads + ", avgReadsPerRegion = " + avgReadsPerRegion + 
				", number of segments > 1 (" + segments + ")!", config);
			LogWriter.dbgLog("lb2/" + chr + "_" + reg, "1b\tminPos = " + minPos + ", maxPos = " + maxPos + ", posRange = " + posRange, config);
		}
		else{
			LogWriter.dbgLog("lb/" + chr + "_" + reg, "1\tStarting to combine the files. Reads = " + reads + ", avgReadsPerRegion = " + 
				avgReadsPerRegion, config);
		}
			
		
		val samRecords = ArrayBuffer.empty[(Integer, SAMRecord)]
		var fileCount = 0
		val shuffledFiles = Random.shuffle(files.toList).toArray
		// Sam writers for each segment of a region with more than one segments
		val samWriters = ArrayBuffer.empty[SAMFileWriter]
		// Alignment positions based on which Multisegmented region would be divided.
		var alnPosArray: Array[Int] = null
		// Reads writen per segment
		val readsWritten = ArrayBuffer.empty[Long]
		
		val threadArray = new Array[Thread](nThreads)
		
		if (segments > 1)
		{
			val header = createHeader(config)
			
			for(a <- 0 until segments)
			{
				val fileName = config.getTmpFolder + chr + "_" + reg + "_part" + a + ".sam"
				val factory = new SAMFileWriterFactory()
				val writer = factory.makeSAMWriter(header, false, new File(fileName))
				// Append a writer for creating a segment of a region
				samWriters.append(writer)
				// Initialize reads count for each segment
				readsWritten.append(0)
			}
			
			LogWriter.dbgLog("lb2/" + chr + "_" + reg, "2a\tGetting the positions. Number of input files = " + shuffledFiles.size, config)
			val contentArray = new Array[StringBuilder](nThreads) 
			var fileInfoPerThread = ArrayBuffer.empty[scala.collection.mutable.ArrayBuffer[String]]
			
			for( a <- 0 until nThreads)
			{
				fileInfoPerThread.append(ArrayBuffer.empty[String])
				contentArray(a) = new StringBuilder
			}
			
			var index = 0
			for (file <- shuffledFiles)
			{
				fileInfoPerThread(index % nThreads).append(file._5) //posFile info
				index += 1
			}
			
			for(thread <- 0 until nThreads)
			{
				// Multithreaded /////////////////////////////////////////////////
				threadArray(thread) = new Thread {
					override def run {
				//////////////////////////////////////////////////////////////////
				for(f <- fileInfoPerThread(thread))
				{
					val fileInfo = f.split(',')
					contentArray(thread).append(FileManager.readPartialFile(config.getOutputFolder + "starPos/" + fileInfo(0), fileInfo(2).toInt, config).slice(fileInfo(1).toInt, fileInfo(2).toInt))
				}
				// Multithreaded /////////////////////////////////////////////////
					}
				}
				threadArray(thread).start
				//////////////////////////////////////////////////////////////////
			}
			// Multithreaded ///////////////////////////////////////////////// 
			for(thread <- 0 until nThreads)
				threadArray(thread).join
			//////////////////////////////////////////////////////////////////
			
			val content = new StringBuilder
			for(thread <- 0 until nThreads)
				content.append(contentArray(thread))
			alnPosArray = content.split('\n').map(x => x.toInt)
			retSegments = segments
			LogWriter.dbgLog("lb2/" + chr + "_" + reg, "2b\tGot all " + alnPosArray.size + " positions in an array.", config)
			//////////////////////////////////////////////////////////////////
			val readsPerSegment = alnPosArray.size / segments
			
			// Sort the position array, so that we can use binary search with it
			scala.util.Sorting.stableSort(alnPosArray)
			
			LogWriter.dbgLog("lb2/" + chr + "_" + reg, "3a\tTotal number of reads = " + alnPosArray.size + ", reads per segment = " + readsPerSegment, config)
			
			fileInfoPerThread = ArrayBuffer.empty[scala.collection.mutable.ArrayBuffer[String]]
			
			for( a <- 0 until nThreads)
				fileInfoPerThread.append(ArrayBuffer.empty[String])
			
			index = 0
			for (file <- shuffledFiles)
			{
				fileInfoPerThread(index % nThreads).append(file._1)
				index += 1
			}
			
			for(thread <- 0 until nThreads)
			{
				// Multithreaded /////////////////////////////////////////////////
				threadArray(thread) = new Thread {
					override def run {
				//////////////////////////////////////////////////////////////////
				var fileCount = 0
				for(f <- fileInfoPerThread(thread))
				{
					val fileInfo = f.split(',')
					var kvPairs: Array[(Integer, SAMRecord)] = null
					
					if (config.getMode == "local") //local downloadsam
					{
						val content = FileManager.readWholeFile(config.getOutputFolder + "samChunks/" + fileInfo(0), config).slice(fileInfo(1).toInt, fileInfo(2).toInt)
				
						// Get sam records from the chunk
						val bwaKeyValues = new SamRecsReader(new StringBufferInputStream(content), config)			
						bwaKeyValues.parseSam(null)
						kvPairs = bwaKeyValues.getKeyValuePairs()
						bwaKeyValues.close()
					}
					else //cluster
					{
						val fname = config.getTmpFolder + fileInfo(0)
						hdfsManager.download(fileInfo(0), config.getOutputFolder + "samChunks/", config.getTmpFolder, false)
						
						// Get sam records from the chunk
						val bwaKeyValues = new SamRecsReader(new FileInputStream(fname), config)			
						bwaKeyValues.parseSam(null)
						kvPairs = bwaKeyValues.getKeyValuePairs()
						bwaKeyValues.close()
						
						new File(fname).delete
					}
					
					for (e <- kvPairs)
					{
						var i = binarySearch(alnPosArray, 0, alnPosArray.size-1, e._2.getAlignmentStart) / readsPerSegment
						
						if (i >= segments)
							i = segments-1
						
						samWriters(i).synchronized
						{
							// Write to corresponding segment
							samWriters(i).addAlignment(e._2)
							readsWritten(i) += 1
						}
					}
						
					fileCount += 1
					if ((fileCount % 50) == 0)
						files.synchronized {LogWriter.dbgLog("lb2/" + chr + "_" + reg, "3b\t" + fileCount + " files processed by thread " + thread, config)}
				}
				files.synchronized {LogWriter.dbgLog("lb2/" + chr + "_" + reg, "3b\tTotal files processed by thread " + thread + " = " + fileCount, config)}
				// Multithreaded /////////////////////////////////////////////////
					}
				}
				threadArray(thread).start
				//////////////////////////////////////////////////////////////////
			}
			// Multithreaded ///////////////////////////////////////////////// 
			for(thread <- 0 until nThreads)
				threadArray(thread).join
			//////////////////////////////////////////////////////////////////
			
			// Make bam files of all the segments
			val iterations =  Math.ceil(segments/nThreads.toFloat).toInt 
			for(iter <- 0 until iterations)
			{
				var nUsedThreads = 0
				for(thread <- 0 until nThreads)
				{
					val a = iter * nThreads + thread
					if (a < segments)
					{
						nUsedThreads += 1
						// Multithreaded /////////////////////////////////////////////////
						threadArray(thread) = new Thread {
							override def run {
						//////////////////////////////////////////////////////////////////
						val inputFile = config.getTmpFolder + chr + "_" + reg + "_part" + a + ".sam"
						samWriters(a).close()
					
						files.synchronized {LogWriter.dbgLog("lb2/" + chr + "_" + reg, "4a\tReads in segment " + a + " = " + readsWritten(a), config)}
						// Get sam records from the segment
						val bwaKeyValues = new SamRecsReader(new FileInputStream(inputFile), config)
						bwaKeyValues.parseSam(null)
						val samRecords: Array[(Integer, SAMRecord)] = bwaKeyValues.getKeyValuePairs()
						bwaKeyValues.close()
			
						files.synchronized {LogWriter.dbgLog("lb2/" + chr + "_" + reg, "4b\tWriting to BAM segment" + a + ". nUsedThreads = " + nUsedThreads, config)}
						writeToBAMAndBed("part" + a + "_" + chr + "_" + reg, samRecords.toArray, 0, samRecords.size, config)
						files.synchronized {LogWriter.dbgLog("lb2/" + chr + "_" + reg, "4c\tDone writing " + samRecords.size + " reads from " + inputFile, config)}
					
						new File(inputFile).delete()
						// Multithreaded /////////////////////////////////////////////////
							}
						}
						threadArray(thread).start
						//////////////////////////////////////////////////////////////////
					}
				}
				
				// Multithreaded /////////////////////////////////////////////////
				for(thread <- 0 until nUsedThreads)
					threadArray(thread).join
				//////////////////////////////////////////////////////////////////
			}
			
			LogWriter.dbgLog("lb2/" + chr + "_" + reg, "5\tDone writing contents of all sam files.", config)
		}
		else
		{
			val fileInfoPerThread = ArrayBuffer.empty[scala.collection.mutable.ArrayBuffer[String]]
			
			for( a <- 0 until nThreads)
				fileInfoPerThread.append(ArrayBuffer.empty[String])
			
			var index = 0
			for (file <- shuffledFiles)
			{
				fileInfoPerThread(index % nThreads).append(file._1)
				index += 1
			}
			
			for(thread <- 0 until nThreads)
			{
				// Multithreaded /////////////////////////////////////////////////
				threadArray(thread) = new Thread {
					override def run {
				//////////////////////////////////////////////////////////////////
				var fileCount = 0
				for (fi <- fileInfoPerThread(thread))
				{
					val fileInfo = fi.split(',')
										
					//val content = FileManager.readWholeFile(config.getOutputFolder + "samChunks/" + fileInfo(0), config).slice(fileInfo(1).toInt, fileInfo(2).toInt)
					val content = FileManager.readPartialFile(config.getOutputFolder + "samChunks/" + fileInfo(0), 
						fileInfo(2).toInt, config).slice(fileInfo(1).toInt, fileInfo(2).toInt)
					
					// Get sam records from the chunk
					val bwaKeyValues = new SamRecsReader(new StringBufferInputStream(content), config)	
					bwaKeyValues.parseSam(null)
					val kvPairs: Array[(Integer, SAMRecord)] = bwaKeyValues.getKeyValuePairs()
					bwaKeyValues.close()
					
					samRecords.synchronized
					{
						for (e <- kvPairs)
							samRecords.append(e)
					}
					
					fileCount += 1
					if ((fileCount % 50) == 0)
						samRecords.synchronized {
							LogWriter.dbgLog("lb/" + chr + "_" + reg, "2\tRead " + fileCount + " files into the array by thread " + thread, config)
						}
				} // End of for
				samRecords.synchronized {
					LogWriter.dbgLog("lb/" + chr + "_" + reg, "2\tTotal files read by thread " + thread + " = " + fileCount, config)
				}
				// Multithreaded /////////////////////////////////////////////////
					}
				}
				threadArray(thread).start
				//////////////////////////////////////////////////////////////////
			}
			// Multithreaded ///////////////////////////////////////////////// 
			for(thread <- 0 until nThreads)
				threadArray(thread).join
			//////////////////////////////////////////////////////////////////
			LogWriter.dbgLog("lb/" + chr + "_" + reg, "3\tRead all files into the array.", config)
			writeToBAMAndBed(chr + "_" + reg, samRecords.toArray, 0, samRecords.size, config)
			LogWriter.dbgLog("lb/" + chr + "_" + reg, "4\tBAM and BED files uploaded to the HDFS.", config)
		}
		
		return ((chr, reg), retSegments)
	}

	def binarySearch(arr: Array[Int], starti: Int, endi: Int, x: Int) : Int =
	{
		if (starti > endi)
			return 0 // If element is not found, we will just put it in segment #0
		
		val guess = starti + (endi - starti) / 2

		if (arr(guess) == x)
			return guess
		
		if ((guess != 0) && (arr(guess-1) == x))
			return guess - 1
			
		if ((guess != endi) && (arr(guess+1) == x))
			return guess + 1
			
		if (arr(guess) > x)
			return binarySearch(arr, starti, guess-1, x)
		else
			return binarySearch(arr, guess+1, endi, x) 
	}

	def writeToBAMAndBed(chrRegion: String, samRecords: Array[(Integer, SAMRecord)], startIndex: Int, endIndex: Int, config: Configuration) : 
		Integer =
	{
		val tmpFileBase =  config.getTmpFolder + chrRegion
		val header = createHeader(config)
		header.setSortOrder(SAMFileHeader.SortOrder.coordinate)
		//////////////////////////////
		val bamrg = new SAMReadGroupRecord(config.getRGID)
		bamrg.setLibrary("LIB1")
		bamrg.setPlatform("ILLUMINA")
		bamrg.setPlatformUnit("UNIT1")
		bamrg.setSample("SAMPLE1")
		header.addReadGroup(bamrg)
		/////////////////////////////
		var part = if (chrRegion.contains("part")) "2" else "";
		val hdfsManager = new HDFSManager
			
		if (config.getMode != "local")
			hdfsManager.create(config.getOutputFolder + "log/lb" + part + "/region_" + chrRegion)
		else
			FileManager.makeDirIfRequired(config.getOutputFolder + "log/lb" + part, config)
		
		if (config.isInIgnoreList(samRecords(0)._2.getReferenceName))
		{
			LogWriter.dbgLog("lb" + part + "/region_" + chrRegion, "0\tChromosome " + samRecords(0)._2.getReferenceName + 
				" is being ignored!", config);
			return 0
		}
		
		// Sorting
		implicit val samRecordOrdering = new Ordering[(Integer, SAMRecord)] {
			override def compare(a: (Integer, SAMRecord), b: (Integer, SAMRecord)) = compareSAMRecords(a._2, b._2)
		}
		if ((startIndex) == 0 && (endIndex == samRecords.size))
		{
			LogWriter.dbgLog("lb" + part + "/region_" + chrRegion, "0a\t" + "quicksort started...", config)
			scala.util.Sorting.stableSort(samRecords)
			LogWriter.dbgLog("lb" + part + "/region_" + chrRegion, "0b\t" + "quicksort done!", config)
		}
		//
		
		val factory = new SAMFileWriterFactory()
		val writer = {
			if ((config.getMode != "local") && writeToHDFSDirectlyInLB)
				factory.makeBAMWriter(header, true,  hdfsManager.openStream(config.getOutputFolder + "bam/" + chrRegion + "-p1.bam"))
			else
				factory.makeBAMWriter(header, true, new File(tmpFileBase + "-p1.bam")) 
		}
		val regionIter = new RegionIterator(samRecords, header, startIndex, endIndex)
		val RGID = config.getRGID
		var count = 0
		var badLines = 0
		while(regionIter.hasNext()) 
		{
			val sam = regionIter.next()
			/////////////////////////////////////////
			sam.setAttribute(SAMTag.RG.name(), RGID)
			/////////////////////////////////////////
			try
			{
				writer.addAlignment(sam)
			}
			catch
			{
				case e: Exception => badLines += 1
			}
			count += 1
			if ((count % 500000) == 0)
				LogWriter.dbgLog("lb" + part + "/region_" + chrRegion, "1\t" + count + " records written. Bad lines = " + badLines, config)
		}
		regionIter.addLastChrRange()
		val reads = regionIter.getCount()
		writer.close
		
		LogWriter.dbgLog("lb" + part + "/region_" + chrRegion, "2\tMaking bed file. Reads = " + reads + ", written = " + count, config)
		makeRegionFile(tmpFileBase, regionIter, config)
		
		if (!writeToHDFSDirectlyInLB)
			FileManager.uploadFileToOutput(tmpFileBase + "-p1.bam", "bam", true, config)
		FileManager.uploadFileToOutput(tmpFileBase + ".bed", "bed", true, config)
		LogWriter.dbgLog("lb" + part + "/region_" + chrRegion, "3\tBAM and bed files uploaded to the HDFS", config)
		
		return reads
	}

	def copyExomeBed(exomeBed: String, config: Configuration)
	{
		val lines = FileManager.readWholeFile(config.getExomePath, config)
		var from = 0
			
		if (lines(0) == '@')
		{
			var done = false
			
			while(!done)
			{
				var next = lines.indexOf('\n', from)
				from = next + 1
				if (lines(from) != '@')
					done = true
			}
		}
			
		new PrintWriter(exomeBed) {write(lines.substring(from)); close}
	}

	def makeCorrectBedFile(cmdStr: String, bedFile: String)
	{
		val cmdRes = (cmdStr #> new java.io.File(bedFile + ".1")).!
		val lines = scala.io.Source.fromFile(bedFile + ".1").mkString.split('\n')
		var s = ""
		
		for( line <- lines)
		{
			val e = line.split('\t')
			if (e.size >= 2)
			{
				val lval = e(1).toLong
				var hval = e(2).toLong
			
				if (lval == hval)
					hval += 1
			
				s += e(0) + "\t" + lval + "\t" + hval + "\n"
			}
		}
		
		new PrintWriter(bedFile) {write(s); close}
		new File(bedFile + ".1").delete()
	}

	def makeRegionFile(tmpFileBase: String, regionIter: RegionIterator, config: Configuration)
	{
		val bedFile = tmpFileBase + ".bed"
		val hdfsManager = new HDFSManager
		
		if (config.useExome())
		{
			val toolsFolder = FileManager.getToolsDirPath(config)
			val exomeBed = tmpFileBase + "_exome.bed"
			
			copyExomeBed(exomeBed, config)
			
			val file = new File(FileManager.getToolsDirPath(config) + "bedtools") 
			file.setExecutable(true)
			
			 // write a bed file with the region!
			val bed = new File(exomeBed + "_tmp.bed")
			regionIter.writeToBedFile(bed.getAbsolutePath())
			makeCorrectBedFile(toolsFolder + "bedtools intersect -a " + exomeBed + " -b " + bed + " -header", bedFile)
			
			// Delete temporary files
			new File(exomeBed).delete()
			new File(exomeBed + "_tmp.bed").delete()
		}
		else
		{
			val bed = new File(bedFile)
			regionIter.writeToBedFile(bed.getAbsolutePath())
		}
	}

	def createSAMWriter(fileName: String, config: Configuration) : SAMFileWriter =
	{
		val header = createHeader(config)
		val factory = new SAMFileWriterFactory()
		return factory.makeSAMWriter(header, false, new File(fileName))
	}

	def compareSAMRecords(a: SAMRecord, b: SAMRecord) : Int = 
	{
		if(a.getReferenceIndex == b.getReferenceIndex)
			return a.getAlignmentStart - b.getAlignmentStart
		else
			return a.getReferenceIndex - b.getReferenceIndex
	}

	def createHeader(config: Configuration) : SAMFileHeader =
	{
		val header = new SAMFileHeader()
		header.setSequenceDictionary(config.getDict())
		
		return header
	}

	def processBAM (chrRegion: String, config: Configuration) : Integer =
	{
		val tmpFileBase = config.getTmpFolder + chrRegion
		val hdfsManager = new HDFSManager
		val downloadRef = config.getSfFolder == "./"
		var testdownloadTime = 0.0
		
		if (config.getMode != "local")
		{
			hdfsManager.create(config.getOutputFolder + "log/vcf/" + "region_" + chrRegion)
				
			if (!(new File(config.getTmpFolder).exists))
				new File(config.getTmpFolder).mkdirs()
			
			LogWriter.dbgLog("vcf/region_" + chrRegion, "2g\tDownloading bam and bed files to the local directory...", config)
			hdfsManager.download(chrRegion + "-p1.bam", config.getOutputFolder + "bam/", config.getTmpFolder, false)
			hdfsManager.download(chrRegion + ".bed", config.getOutputFolder + "bed/", config.getTmpFolder, false)
			LogWriter.dbgLog("vcf/region_" + chrRegion, "2h\tCompleted download of bam and bed to the local directory!", config)
		}
		else
			FileManager.makeDirIfRequired(config.getOutputFolder + "log/vcf", config)
		
		var f = new File(tmpFileBase + "-p1.bam");
		if(f.exists() && !f.isDirectory()) 
			LogWriter.dbgLog("vcf/region_" + chrRegion, "*+\tBAM file does exist in the tmp directory!", config)
		else
			LogWriter.dbgLog("vcf/region_" + chrRegion, "*-\tBAM file does not exist in the tmp directory!", config)
		
		f = new File(tmpFileBase + ".bed");
		if(f.exists() && !f.isDirectory()) 
			LogWriter.dbgLog("vcf/region_" + chrRegion, "#+\tbed file does exist in the tmp directory!!", config)
		else
			LogWriter.dbgLog("vcf/region_" + chrRegion, "#-\tbed file does not exist in the tmp directory!!", config)
		
		LogWriter.dbgLog("vcf/region_" + chrRegion, "3\tPicard processing started", config)
		if (downloadRef && (config.getMode != "local"))
		{
			LogWriter.dbgLog("vcf/region_" + chrRegion, "*\tDownloading VCF ref files", config)
			val t0 = System.currentTimeMillis
			FileManager.downloadVCFRefFiles("vcf/region_" + chrRegion, config)
			testdownloadTime = (System.currentTimeMillis - t0) / 1000
			LogWriter.dbgLog("vcf/region_" + chrRegion, "*\tDownloading ref time = "+ testdownloadTime + " seconds.", config)
		}
		
		var cmdRes = picardPreprocess(tmpFileBase, config)
		LogWriter.dbgLog("vcf/region_" + chrRegion, "*\tpicard processing finished.", config)
		
		if (downloadRef && (config.getMode != "local"))
		{
			LogWriter.dbgLog("vcf/region_" + chrRegion, "*\tDownloading VCF index files", config)
			val t0 = System.currentTimeMillis
			FileManager.downloadVCFIndexFiles("vcf/region_" + chrRegion, config)
			testdownloadTime = (System.currentTimeMillis - t0) / 1000
			LogWriter.dbgLog("vcf/region_" + chrRegion, "*\tDownloading vcf index time = "+ testdownloadTime + " seconds.", config)
		}

		cmdRes += splitNCigarReads(tmpFileBase, chrRegion, config)
		if (config.doIndelRealignment)
			cmdRes += indelRealignment(tmpFileBase, chrRegion, config)
		
		cmdRes += baseQualityScoreRecalibration(tmpFileBase, chrRegion, config)
		
		cmdRes += DnaVariantCalling(tmpFileBase, chrRegion, config)
		
		if (config.getMode() != "local")
		{
			new File(config.getTmpFolder() + "." + chrRegion + ".vcf.crc").delete()
			hdfsManager.upload(chrRegion + ".vcf", config.getTmpFolder, config.getOutputFolder + "vcfs/", true)
		}
		
		LogWriter.dbgLog("vcf/region_" + chrRegion, "9\tOutput written to vcf file. r = " + cmdRes + " (r > 0 implies error(s))", config)
		return cmdRes
	}

	def variantCall (chrRegion: String, config: Configuration) : (String, Integer) =
	{
		return (chrRegion, processBAM(chrRegion, config))
	}

	def picardPreprocess(tmpFileBase: String, config: Configuration) : Integer =
	{
		val toolsFolder = FileManager.getToolsDirPath(config)
		val tmpOut1 = tmpFileBase + "-p1.bam"
		//val tmpOut2 = tmpFileBase + "-p2.bam"
		val MemString = config.getExecMemX()
		
		var t0 = System.currentTimeMillis

		/* var cmdStr = "java " + MemString + " -jar " + toolsFolder + "picard.jar CleanSam I=" + tmpOut1 + " O=" + tmpOut2
		var cmdRes = cmdStr.! */
		
		val bamOut = tmpFileBase + ".bam"
		val tmpMetrics = tmpFileBase + "-metrics.txt"
		
		val cmdStr = "java " + MemString + " -jar " + toolsFolder + "picard.jar MarkDuplicates I=" + tmpOut1 + " O=" + bamOut +
			" METRICS_FILE=" + tmpMetrics + " CREATE_INDEX=true" + " VALIDATION_STRINGENCY=SILENT ASSUME_SORTED=true";
		val cmdRes = cmdStr.!
		
		// Hamid - Save output of picardPreprocessing
		if (saveAllStages)
			FileManager.uploadFileToOutput(bamOut, "picardOutput", false, config)
		
		// Delete temporary files
	
		new File(tmpOut1).delete()
		//new File(tmpOut2).delete()
		new File(tmpMetrics).delete()
		
		return cmdRes
	}

	def splitNCigarReads(tmpFileBase: String, chrRegion: String, config: Configuration) : Integer = {
        val toolsFolder = FileManager.getToolsDirPath(config)
		val preprocess = tmpFileBase + ".bam" //input
		val tmpOutput = tmpFileBase + "-s.bam"
		val regionStr = " -L " + tmpFileBase + ".bed"
		val MemString = config.getExecMemX()

		var cmdStr = "java " + MemString + " " + config.getGATKopts + " -jar " + toolsFolder + "GenomeAnalysisTK.jar -T SplitNCigarReads" + " -R " + 
		    FileManager.getRefFilePath(config) + " -I " + preprocess + " -o " + tmpOutput + " -rf ReassignOneMappingQuality -RMQT 60 -RMQF 255 -fixNDN " + "-U ALLOW_N_CIGAR_READS" + regionStr
		var cmdRes = cmdStr.!

		// Hamid - Save output of indelRealignment
		if (saveAllStages)
			FileManager.uploadFileToOutput(tmpOutput, "splitNCigarReads", false, config)
		// saiyi any tmp files?

		return cmdRes
	}

	def indelRealignment(tmpFileBase: String, chrRegion: String, config: Configuration) : Integer =
	{
		val toolsFolder = FileManager.getToolsDirPath(config)
		val knownIndel = FileManager.getIndelFilePath(config)
		val tmpFile1 = tmpFileBase + "-2.bam"
		val sInput = tmpFileBase + "-s.bam"
		val targets = tmpFileBase + ".intervals"
		val MemString = config.getExecMemX()
		val regionStr = " -L " + tmpFileBase + ".bed"
		val indelStr = if (config.useKnownIndels) (" -known " + knownIndel) else ""; 
		
		// Realigner target creator
		var cmdStr = "java " + MemString + " " + config.getGATKopts + " -jar " + toolsFolder + "GenomeAnalysisTK.jar -T RealignerTargetCreator -nt " + 
			config.getNumThreads() + " -R " + FileManager.getRefFilePath(config) + " -I " + sInput + indelStr + " -o " + targets + regionStr
		LogWriter.dbgLog("vcf/region_" + chrRegion, "4\t" + cmdStr, config)
		var cmdRes = cmdStr.!
		
		// Indel realigner
		cmdStr = "java " + MemString + " " + config.getGATKopts + " -jar " + toolsFolder + "GenomeAnalysisTK.jar -T IndelRealigner -R " + 
			FileManager.getRefFilePath(config) + " -I " + sInput + " -targetIntervals " + targets + indelStr + " -o " + tmpFile1 + regionStr
		LogWriter.dbgLog("vcf/region_" + chrRegion, "5\t" + cmdStr, config)
		cmdRes += cmdStr.!
		
		// Hamid - Save output of indelRealignment
		if (saveAllStages)
			FileManager.uploadFileToOutput(tmpFile1, "indelOutput", false, config)
		
		// Delete temporary files
		
		new File(sInput).delete()
		new File(sInput.replace(".bam", ".bai")).delete()
		new File(targets).delete()
		
		return cmdRes
	}

	def baseQualityScoreRecalibration(tmpFileBase: String, chrRegion: String, config: Configuration) : Integer =
	{
		val toolsFolder = FileManager.getToolsDirPath(config)
		val knownIndel = FileManager.getIndelFilePath(config)
		val knownSite = FileManager.getSnpFilePath(config)
		val tmpFile1 = if (config.doIndelRealignment) (tmpFileBase + "-2.bam") else (tmpFileBase + "-s.bam")
		val tmpFile2 = tmpFileBase + "-3.bam"
		val table = tmpFileBase + ".table"
		val MemString = config.getExecMemX()
		val regionStr = " -L " + tmpFileBase + ".bed"
		val indelStr = if (config.useKnownIndels) (" -knownSites " + knownIndel) else ""; 
		
		// Base recalibrator
		var cmdStr = "java " + MemString + " " + config.getGATKopts + " -jar " + toolsFolder + "GenomeAnalysisTK.jar -T BaseRecalibrator -nct " + 
			config.getNumThreads() + " -R " + FileManager.getRefFilePath(config) + " -I " + tmpFile1 + " -o " + table + regionStr + 
			" --disable_auto_index_creation_and_locking_when_reading_rods" + indelStr + " -knownSites " + knownSite
		LogWriter.dbgLog("vcf/region_" + chrRegion, "6\t" + cmdStr, config)
		var cmdRes = cmdStr.!

		if (config.doPrintReads)
		{
			cmdStr = "java " + MemString + " " + config.getGATKopts + " -jar " + toolsFolder + "GenomeAnalysisTK.jar -T PrintReads -R " + 
				FileManager.getRefFilePath(config) + " -I " + tmpFile1 + " -o " + tmpFile2 + " -BQSR " + table + regionStr 
			LogWriter.dbgLog("vcf/region_" + chrRegion, "7\t" + cmdStr, config)
			cmdRes += cmdStr.!
			// Hamid - Save output of baseQualityScoreRecalibration
			if (saveAllStages)
				FileManager.uploadFileToOutput(tmpFile2, "baseOutput", false, config)
		
			// Delete temporary files
			
			new File(tmpFile1).delete()
			new File(tmpFile1.replace(".bam", ".bai")).delete()
			new File(table).delete()
		}
		
		return cmdRes
	}

	def DnaVariantCalling(tmpFileBase: String, chrRegion: String, config: Configuration) : Integer =
	{
		val toolsFolder = FileManager.getToolsDirPath(config)
		val tmpFile2 = if (config.doPrintReads) (tmpFileBase + "-3.bam") else if (config.doIndelRealignment) (tmpFileBase + "-2.bam") else (tmpFileBase + "-s.bam")
		val snps = tmpFileBase + ".vcf"
		val bqsrStr = if (config.doPrintReads) "" else (" -BQSR " + tmpFileBase + ".table ")
		val MemString = config.getExecMemX()
		val regionStr = " -L " + tmpFileBase + ".bed"
		val rnaStr = " -dontUseSoftClippedBases -stand_call_conf 20.0 "
		// Hamid
		val standconf = if (config.getSCC == "0") " " else (" -stand_call_conf " + config.getSCC)
		val standemit = if (config.getSEC == "0") " " else (" -stand_emit_conf " + config.getSEC)
		
		// Haplotype caller
		var cmdStr = "java " + MemString + " " + config.getGATKopts + " -jar " + toolsFolder + "GenomeAnalysisTK.jar -T HaplotypeCaller -nct " + 
			config.getNumThreads() + " -R " + FileManager.getRefFilePath(config) + " -I " + tmpFile2 + bqsrStr + " --genotyping_mode DISCOVERY -o " + snps + 
			standconf + standemit + regionStr + " --no_cmdline_in_header --disable_auto_index_creation_and_locking_when_reading_rods" + rnaStr
		LogWriter.dbgLog("vcf/region_" + chrRegion, "8\t" + cmdStr, config)
		var cmdRes = cmdStr.!
		
		// Delete temporary files
		
		new File(tmpFile2).delete()
		new File(tmpFile2.replace(".bam", ".bai")).delete()
		new File(tmpFileBase + ".bed").delete()
		if (!config.doPrintReads)
			new File(tmpFileBase + ".table").delete
		
		return cmdRes
	}

	def getVCF(chrRegion: String, config: Configuration) : Array[((Integer, Integer), String)] =
	{
		var a = scala.collection.mutable.ArrayBuffer.empty[((Integer, Integer), String)]
		var fileName = config.getTmpFolder() + chrRegion + ".vcf"
		var commentPos = 0
		val hdfsManager = new HDFSManager
		
		if (config.getMode() != "local")
			hdfsManager.download(chrRegion + ".vcf", config.getOutputFolder + "vcfs/", config.getTmpFolder, false)
		
		if (!Files.exists(Paths.get(fileName)))
			return a.toArray
		
		for (line <- Source.fromFile(fileName).getLines()) 
		{
			val c = line(0)
			if (c != '#')
			{
				val e = line.split('\t')
				val position = e(1).toInt
				var chromosome = e(0)
				var chrNumber = config.getChrIndex(chromosome)
					
				a.append(((chrNumber, position), line))
			}
			else
			{
				a.append(((-1, commentPos), line))
				commentPos = commentPos + 1
			}
		}
		
		// Delete temporary files
		if (config.getMode != "local")
			new File(fileName).delete()
		new File(fileName + ".idx").delete()
		
		return a.toArray
	}

	def regenerateGenome(x:Int, config:Configuration) : Int={
		    val logName = "regenerationGenome.log"
            var sjFile = ""
			val hdfsManager = new HDFSManager
			val downloadRef = config.getSfFolder == "./"
			
			if (config.getMode != "local"){
			    hdfsManager.createIfRequired(config.getOutputFolder + "log/star/" + logName)
				hdfsManager.download("mergedPass1_SJ.out.tab", config.getOutputFolder, config.getTmpFolder, false)
				sjFile = config.getTmpFolder + "mergedPass1_SJ.out.tab"

				if(downloadRef){
				LogWriter.dbgLog("star/" + logName, "*\tDownloading reference if required.", config)
				FileManager.downloadRNARef(config)
			    }

				FileManager.deleteSTARIndex("1", config)
				if(config.getSTAR1onLocal){
					LogWriter.dbgLog("star/" + logName, "*\tGenome 1 is put in local storage. Has not deleted.", config)
				}
				else{
                    LogWriter.dbgLog("star/" + logName, "*\tDeleted genome index of pass1 in the local tmp directory.", config)
				}
		    }
		   else{
		       FileManager.makeDirIfRequired(config.getOutputFolder + "log/star", config)
			   sjFile = config.getOutputFolder + "mergedPass1_SJ.out.tab"
			}

			val fastaFile = FileManager.getRefFileDire(config) + "ucsc.hg19.fasta"
			
			val tmpDir = config.getTmpFolder
			val genomeDire = FileManager.getStarIndexLocalDire("2",config)
			val progName = FileManager.getToolsDirPath(config) + "STAR "
			val readLength = "100" //may optimize later
			val sparseDistance = "8"//may optimize later
			val outFilePrefix = tmpDir+"reGenerate_"
			val nthreads = java.lang.Runtime.getRuntime.availableProcessors

			FileManager.cleanAndMakeLocalDir(genomeDire, config)

			
		   val command_str = progName + "--genomeDir "  + genomeDire + " --outFileNamePrefix " + outFilePrefix + 
		                     " --runMode genomeGenerate --genomeFastaFiles " + fastaFile + " --sjdbFileChrStartEnd " + sjFile +
							 " --sjdbOverhang " + readLength + " --runThreadN " + nthreads + " --genomeSAsparseD " + sparseDistance
				
	       LogWriter.dbgLog("star/" + logName, "*\tStarting the regeneration job: -> " + command_str, config)
		
		   var cmd_res = command_str.!

		   LogWriter.dbgLog("star/" + logName, "*\tFinished regeneration.", config)


           if (config.getMode != "local"){
               hdfsManager.uploadFolder(genomeDire, config.getOutputFolder, false) //saiyi: changed to false so this node does not need to redownload
               LogWriter.dbgLog("star/" + logName, "*\tUploaded new index to hdfs.", config)

			   new File(sjFile).delete()
		   }
		       
		   new File(outFilePrefix+"Log.out").delete()

		   return cmd_res
	}
		
	def main(args: Array[String]) 
	{
		val conf = new SparkConf().setAppName("SparkGA1")
		val sc = new SparkContext(conf)
		val appID = sc.applicationId
		val config = new Configuration()
		config.initialize(args(0), sc.deployMode, args(1))
		val part = args(1).toInt
		
		config.print() 
		
		val bcConfig = sc.broadcast(config)
		val hdfsManager = new HDFSManager
		
		if (part == 1)
		{
			if (config.getMode != "local")
				hdfsManager.create("sparkLog.txt")
			else
				FileManager.makeDirIfRequired(config.getOutputFolder + "log", config)
		}
		
		hdfsManager.synchronized
		{
			LogWriter.statusLog("Started:", "Part " + part + ", Application ID: " + appID, config)
		}
		
		var t0 = System.currentTimeMillis
		val numOfRegions = config.getNumRegions().toInt
		// Spark Listener
		sc.addSparkListener(new SparkListener() 
		{
			override def onStageCompleted(stageCompleted: SparkListenerStageCompleted) 
			{
				hdfsManager.synchronized
				{
					val map = stageCompleted.stageInfo.rddInfos
					map.foreach(row => {
						if (row.isCached)
						{
							LogWriter.statusLog("SparkListener:", row.name + ": memSize = " + (row.memSize / (1024*1024)) + 
									"MB, diskSize " + row.diskSize + ", numPartitions = " + row.numPartitions + "-" + row.numCachedPartitions, config)
						}
						else if (row.name.contains("rdd_"))
						{
							LogWriter.statusLog("SparkListener:", row.name + " processed!", config)
						}
					})
				}
			}
		});
		//////////////////////////////////////////////////////////////////////////
		if (part == 1)
		{
			val noTask = config.getNumTasks.toInt
			var noPartition = noTask
			var inputData = sc.parallelize(Array("spark", "genome")) //randomly define an RDD of type String
			val paired = config.getPaired()

            if(config.getMode!="local"){
					val noInstance = config.getNumInstance.toInt
					noPartition = noTask*noInstance
				    var noExecutor = sc.getExecutorMemoryStatus.size - 1
				    LogWriter.statusLog("Before waiting: ", "NO of executors:"+noExecutor, config)

				    while((sc.getExecutorMemoryStatus.size - 1)!=noInstance) {
				        Thread.sleep(1000)
				        LogWriter.statusLog("Waiting for executors:", "to be registered.", config)
				    }
				    noExecutor = sc.getExecutorMemoryStatus.size - 1
				    LogWriter.statusLog("after waiting: ", "NO of executors:"+noExecutor, config)
            }

			
            //saiyi test haven't changed streaming to paired case
			if (config.doStreamingBWA)
			{
				var done = false
				val parTasks = config.getChunkerGroupSize.toInt
				var si = 0
				var ei = parTasks
				while(!done)
				{
					var indexes = (si until ei).toArray  
					val inputDataS = sc.parallelize(indexes, noPartition)
					val starOutput1 = inputDataS.mapPartitionsWithIndex{ (index, iter) =>
				        if(iter.isEmpty){
						    Iterator[Int]()
					    }
					    else{
                            starGenomeLoad("1", "LoadAndExit", index.toString, config)
						    iter.map(x=>starRun(x + ".fq", bcConfig.value))
						//iter
					    }
				    }      
				    if(starOutput1.collect.contains(-10000)) done=true
		
					si += parTasks
					ei += parTasks
				}

				val inputArray = FileManager.getInputFileNames(config.getInputFolder, config).filter(x => x.contains(".fq")).map(x => x.replace(".gz", "")).distinct
			    if (inputArray == null)
			    {
					println("The input directory does not exist!")
					System.exit(1)
				}
				scala.util.Sorting.quickSort(inputArray)
				inputArray.foreach(println)
				inputData = sc.parallelize(inputArray, noPartition).cache //

				//may optimize with smaller inputData and use map
				val testAlign1Remove = inputData.mapPartitionsWithIndex{ (index, iter) =>
				    if(iter.isEmpty){
						Iterator[String]()
					}
					else{
						val directory=starGenomeLoad("1", "Remove", index.toString, config)
						var res = for (e <- iter ) yield directory
                        res
					}
				}
				testAlign1Remove.collect 
			}
		    else
			{
				var inputArray = Array[String]()
				if(paired){
                    inputArray = FileManager.getInputFileNames(config.getInputFolder, config).filter(x => x.contains(".fq")).map(x => x.split('-')(0)).distinct
				}
				else{
					inputArray = FileManager.getInputFileNames(config.getInputFolder, config).filter(x => x.contains(".fq")).map(x => x.split('.')(0)).distinct
				}
				if (inputArray == null)
				{
					println("The input directory does not exist!")
					System.exit(1)
				}
				scala.util.Sorting.quickSort(inputArray)
				inputArray.foreach(println)
				inputData = sc.parallelize(inputArray, noPartition).cache //
				
				try{ //add finally block to ensure the remove to be executed
				//benchmark saiyi
				val t011 = System.currentTimeMillis

				val testStarOutput = inputData.mapPartitionsWithIndex{ (index, iter) =>
				    if(iter.isEmpty){
						Iterator[Array[((Integer, Integer, Integer), String)]]()
					}
					else{
                        starGenomeLoad("1", "LoadAndExit", index.toString, config)
						iter.map(x=>starRun(x, bcConfig.value))
						//iter
					}
				}
				val pass1SJArray = testStarOutput.flatMap(x=>x).reduceByKey((a, b)=>a).sortByKey().map(_._2 + '\n').collect
				
				val writer = {
					if (config.getMode == "local")
						new PrintWriter(config.getOutputFolder + "mergedPass1_SJ.out.tab")
					else
						hdfsManager.open(config.getOutputFolder + "mergedPass1_SJ.out.tab")
				}
				for(e <- pass1SJArray)
					writer.write(e)
				writer.close

                //benchmark saiyi
				var et = (System.currentTimeMillis - t011) / 1000
				LogWriter.statusLog("Alignment 1 : ", "Elapsed time: "+et.toString + " secs", config)
                } finally {
				//may optimize with smaller inputData and use map
				val testAlign1Remove = inputData.mapPartitionsWithIndex{ (index, iter) =>
				    if(iter.isEmpty){
						Iterator[String]()
					}
					else{
						val directory=starGenomeLoad("1", "Remove", index.toString, config)
						var res = for (e <- iter ) yield directory
                        res
					}
				}
				testAlign1Remove.collect 
				}
			}//end of no streaming

				//benchmark saiyi
			    val t013 = System.currentTimeMillis

				val singleTask = sc.parallelize(1 to 1,1)
				val regeneration = singleTask.map(x=>regenerateGenome(x,config)).collect //the genome one the same datanode will be deleted first

				//benchmark saiyi
				var et3 = (System.currentTimeMillis - t013) / 1000
				LogWriter.statusLog("re generate : ", "Elapsed time: "+et3.toString + " secs", config)

				var starOutput : Array[((Integer, Integer), (String, Long, Int, Int, String))] = null////////
                
				try{
				//benchmark saiyi
			    val t014 = System.currentTimeMillis

				val testStarAlign2 = inputData.mapPartitionsWithIndex{ (index, iter) =>
				    if(iter.isEmpty){
						Iterator[Array[((Integer, Integer), (String, Long, Int, Int, String))]]()
					}
					else{
						FileManager.deleteSTARIndex("1",config) //delete 1st genome only if in cluster mode
                        starGenomeLoad("2", "LoadAndExit", index.toString, config)
						iter.map(x=>starRun2(x, bcConfig.value))
						//iter
					}
				}
				starOutput = testStarAlign2.flatMap(x=>x).collect

				//benchmark saiyi
				var et4 = (System.currentTimeMillis - t014) / 1000
				LogWriter.statusLog("Alignment 2 : ", "Elapsed time: "+et4.toString + " secs", config)
				} finally {
                
				//may optimize with smaller inputData and use map
				val testAlign2Remove = inputData.mapPartitionsWithIndex{ (index, iter) =>
				    if(iter.isEmpty){
						Iterator[String]()
					}
					else{
						val directory=starGenomeLoad("2", "Remove", index.toString, config)
						var res = for (e <- iter ) yield directory
                        res
					}
				}
				val starLocalFolderArray = testAlign2Remove.collect

				val lockDir=starLocalFolderArray(0)
				val testDelete = inputData.mapPartitionsWithIndex{ (index, iter) =>
				    if(iter.isEmpty){
						Iterator[String]()
					}
					else{
                        deleteStarLocalContent(lockDir,index.toString,config) 
						iter
					}
				}
				testDelete.collect
				}

                //benchmark saiyi
				val t015 = System.currentTimeMillis

                var starOutStr = new StringBuilder
				for(e <- starOutput)
				{
					val chr = e._1._1
					val reg = e._1._2
					val fname = e._2._1
					val reads = e._2._2
					val minPos = e._2._3
					val maxPos = e._2._4
					val posFname = e._2._5
				
					starOutStr.append(chr + "\t" + reg + "\t" + fname + "\t" + reads + "\t" + minPos + "\t" + maxPos + "\t" + posFname + "\n")
				}
				    
                FileManager.makeDirIfRequired(config.getOutputFolder + "starOut", config)
			    FileManager.writeWholeFile(config.getOutputFolder + "starOut.txt", starOutStr.toString, config)

				//benchmark saiyi
				var et5 = (System.currentTimeMillis - t015) / 1000
				LogWriter.statusLog("Sam chunks: ", "Elapsed time: "+et5.toString + " secs", config)
			
		}
		else if (part == 2)
		{
			//benchmark saiyi
			val t021 = System.currentTimeMillis
			
			val input = ArrayBuffer.empty[((Integer, Integer), (String, Long, Int, Int, String))]
			val s = scala.collection.mutable.Set.empty[(Integer, Integer)]
			val inputLinesArray = FileManager.readWholeFile(config.getOutputFolder + "starOut.txt", config).split('\n')
				
			for( x <- inputLinesArray)
			{
				val e = x.split('\t')
				input.append(((e(0).toInt, e(1).toInt), (e(2), e(3).toLong, e(4).toInt, e(5).toInt, e(6))))
				s.add((e(0).toInt, e(1).toInt))
			}
			
			hdfsManager.synchronized
			{
				LogWriter.statusLog("Input Size:", input.size.toString, config)
			}

			//benchmark saiyi
			var et1 = (System.currentTimeMillis - t021) / 1000
			LogWriter.statusLog("=============From file to input array buffer: ", "Elapsed time: "+et1.toString + " secs=============", config)

			//benchmark saiyi
			val t022 = System.currentTimeMillis
			
			val inputArray = input.toArray
			// <(chr, reg), (fname, numOfReads, minPos, maxPos)>
			val inputData = sc.parallelize(inputArray, s.size)
			inputData.cache()
			val totalReads = inputData.map(x => x._2._2).reduce(_+_)
			hdfsManager.synchronized
			{
				LogWriter.statusLog("Total Reads:", totalReads.toString, config)
			}
			// <(chr, reg), Array((fname, numOfReads, minPos, maxPos))>
			val chrReg = inputData.groupByKey
			chrReg.cache()
			chrReg.setName("rdd_chrReg")
			val avgReadsPerRegion = totalReads / chrReg.count
			hdfsManager.synchronized
			{
				LogWriter.statusLog("chrReg:", "Chr regions:" + chrReg.count + ", Total reads: " + avgReadsPerRegion, config)
			}
			// <(chr, reg), segments>

			//benchmark saiyi
			var et2 = (System.currentTimeMillis - t022) / 1000
			LogWriter.statusLog("=============before load balance: ", "Elapsed time: "+et2.toString + " secs=============", config)
			//benchmark saiyi
			val t023 = System.currentTimeMillis

			val loadBalRegions = 
			{
				if (!sizeBasedLBScheduling)
					chrReg.map(x => makeBAMFiles(x._1, x._2.toArray, avgReadsPerRegion, bcConfig.value))
				else
				{
					val chrRegByReads = chrReg.map(x => (x._1, x._2, x._2.map(_._2).reduce(_+_))).sortBy(_._3, false)
					chrRegByReads.map(x => makeBAMFiles(x._1, x._2.toArray, avgReadsPerRegion, bcConfig.value))
				}
			}
			loadBalRegions.cache
			loadBalRegions.setName("rdd_loadBalRegions")
			//////////////////////////////////////////////////////////////////////
			
			
			

			val x = loadBalRegions.collect
			//benchmark saiyi
			var et3 = (System.currentTimeMillis - t023) / 1000
			LogWriter.statusLog("=============load balance: ", "Elapsed time: "+et3.toString + " secs=============", config)
			//benchmark saiyi
			val t024 = System.currentTimeMillis

			var segmentsStr = new StringBuilder
			var singleSegmentStr = new StringBuilder
			var totalRegions = 0
			for(e <- x)
			{
				val chr = e._1._1
				val reg = e._1._2
				val segments = e._2
				
				totalRegions += segments
				
				if (segments > 1)
					segmentsStr.append(chr + "\t" + reg + "\t" + segments + "\n")
				else
					singleSegmentStr.append(chr + "\t" + reg + "\t" + segments + "\n")
			}
			
			FileManager.writeWholeFile(config.getOutputFolder + "log/regions.txt", segmentsStr.toString + "=================================\n" + 
				singleSegmentStr + "=================================\nTotal number of regions = " + totalRegions, config)

			//benchmark saiyi
			var et4 = (System.currentTimeMillis - t024) / 1000
			LogWriter.statusLog("=============write regions.txt: ", "Elapsed time: "+et4.toString + " secs=============", config)
		}
		else // (part == 3)
		{
			// For sorting
			implicit val vcfOrdering = new Ordering[(Integer, Integer)] {
				override def compare(a: (Integer, Integer), b: (Integer, Integer)) = if (a._1 == b._1) a._2 - b._2 else a._1 - b._1;
			}
			//
			
			var inputFileNames: Array[String] = null
			if (config.getMode != "local") 
				inputFileNames = FileManager.getInputFileNames(config.getOutputFolder + "bed/", config).map(x => x.replace(".bed", ""))
			else 
				inputFileNames = FileManager.getInputFileNames(config.getTmpFolder, config).filter(x => x.contains(".bed")).map(x => x.replace(".bed", ""))
			
			//////////////////////////////////////////////////////////////////////
			val inputData = 
			{
				if (!sizeBasedVCScheduling)
				{
					inputFileNames.foreach(println)
					sc.parallelize(inputFileNames, inputFileNames.size)
				}
				else
				{
					val inputFileNamesWithSize = inputFileNames.map(x => (x, FileManager.getBAMFileSize(config.getOutputFolder + "bam/" + x + "-p1.bam", config)))
					val fileNamesBySize = inputFileNamesWithSize.sortWith(_._2 > _._2).map(_._1)
					fileNamesBySize.foreach(println)
					sc.parallelize(fileNamesBySize, fileNamesBySize.size)
				}
			}
			//////////////////////////////////////////////////////////////////////
			inputData.setName("rdd_inputData")
			val vcRes = inputData.map(x => variantCall(x, bcConfig.value)).cache

			vcRes.setName("rdd_vcRes")
			val successfulSb = new StringBuilder
			val failedSb = new StringBuilder
			for(e <- vcRes.collect)
			{
				if (e._2 > 0)
					failedSb.append(e._1 + '\n')
				else
					successfulSb.append(e._1 + '\n')
			}
			hdfsManager.synchronized
			{
				LogWriter.statusLog("vcf-tasks:", "\nSuccessful tasks:\n" + successfulSb.toString + 
					"=======================================================\nFailed tasks:\n" + failedSb.toString + 
					"=======================================================", config)
			}
			
			val vcf = vcRes.flatMap(x=> getVCF(x._1, bcConfig.value))
			vcf.setName("rdd_vcf")
			try
			{
				//vcf.distinct.sortByKey().map(_._2).coalesce(1, false).saveAsTextFile(config.getOutputFolder + "combinedVCF")
				val vcfCollected = vcf.distinct.sortByKey().map(_._2 + '\n').collect
				val writer = {
					if (config.getMode == "local")
						new PrintWriter(config.getOutputFolder + "sparkCombined.vcf")
					else
						hdfsManager.open(config.getOutputFolder + "sparkCombined.vcf")
				}
				for(e <- vcfCollected)
					writer.write(e)
				writer.close
			}
			finally 
			{
				// Close spark context
				sc.stop()
			}
		}
		//////////////////////////////////////////////////////////////////////////
		var et = (System.currentTimeMillis - t0) / 1000
		hdfsManager.synchronized
		{
			LogWriter.statusLog("Ended:", "Part " + part + ", Application ID: " + appID + ". Time taken = " + et.toString() + " secs", config)
		}
		sc.stop()
	}
	//////////////////////////////////////////////////////////////////////////////
} // End of Class definition
