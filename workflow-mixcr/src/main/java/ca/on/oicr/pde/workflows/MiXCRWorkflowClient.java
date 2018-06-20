package ca.on.oicr.pde.workflows;

import ca.on.oicr.pde.utilities.workflows.OicrWorkflow;
import java.util.Map;
import java.util.logging.Logger;
import net.sourceforge.seqware.pipeline.workflowV2.model.Command;
import net.sourceforge.seqware.pipeline.workflowV2.model.Job;
import net.sourceforge.seqware.pipeline.workflowV2.model.SqwFile;

/**
 * <p>
 * For more information on developing workflows, see the documentation at
 * <a href="http://seqware.github.io/docs/6-pipeline/java-workflows/">SeqWare
 * Java Workflows</a>.</p>
 *
 * Quick reference for the order of methods called: 1. setupDirectory 2.
 * setupFiles 3. setupWorkflow 4. setupvironment 5. buildWorkflow
 *
 * See the SeqWare API for
 * <a href="http://seqware.github.io/javadoc/stable/apidocs/net/sourceforge/seqware/pipeline/workflowV2/AbstractWorkflowDataModel.html#setupDirectory%28%29">AbstractWorkflowDataModel</a>
 * for more information.
 */
public class MiXCRWorkflowClient extends OicrWorkflow {

    //dir
    private String dataDir, tmpDir;
    private String outDir;
   
    // Input Data
    private String read1Fastq;
    private String read2Fastq;
    private String outputFilenamePrefix;
    
     //varscan intermediate file names
    private String alignvdjcaFile;
    private String alignrescued1File;
    private String alignrescued2File;
    private String alignrescued2extendFile;
    private String cloneClnsFile;
    private String cloneDetTxtFile;
    
    private String exports;
    
    
    //Tools
    private String mixcr;
    private String javahome;


    //Memory allocation
    private Integer mixcrMem;


    //path to bin
    private String bin;


    private boolean manualOutput;
    private static final Logger logger = Logger.getLogger(MiXCRWorkflowClient.class.getName());
    private String queue;
    private Map<String, SqwFile> tempFiles;
    
    // meta-types
    private final static String TXT_METATYPE = "text/plain";
    private static final String FASTQ_GZIP_MIMETYPE = "chemical/seq-na-fastq-gzip";
    
    private void init() {
        try {
            //dir
            dataDir = "data";
            tmpDir = getProperty("tmp_dir");
            
            // input samples 
              // input samples 
            read1Fastq = getProperty("input_read1_fastq");
            read2Fastq = getProperty("input_read2_fastq");

            //Ext id
            outputFilenamePrefix = getProperty("external_name");

            //Programs
            mixcr = getProperty("MIXCR");
            javahome = getProperty("JAVA_HOME");
            
            manualOutput = Boolean.parseBoolean(getProperty("manual_output"));
            queue = getOptionalProperty("queue", "");

            // mixcr
            mixcrMem = Integer.parseInt(getProperty("mixcr_mem"));
            exports = "export LD_LIBRARY_PATH=" + this.javahome + "/lib" + ":$LD_LIBRARY_PATH" +";" + 
                    "export LD_LIBRARY_PATH=" + this.javahome + "/jre/lib/amd64/server" + ":$LD_LIBRARY_PATH" + ";" +
                    "export PATH=" + this.javahome + "/bin" + ":$PATH" + ";";
   


        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setupDirectory() {
        init();
        this.addDirectory(dataDir);
        this.addDirectory(tmpDir);
        if (!dataDir.endsWith("/")) {
            dataDir += "/";
        }
        if (!tmpDir.endsWith("/")) {
            tmpDir += "/";
        }
    }

    @Override
    public Map<String, SqwFile> setupFiles() {
        SqwFile file0 = this.createFile("read1");
        file0.setSourcePath(read1Fastq);
        file0.setType(FASTQ_GZIP_MIMETYPE);
        file0.setIsInput(true);
        SqwFile file1 = this.createFile("read2");
        file1.setSourcePath(read2Fastq);
        file1.setType(FASTQ_GZIP_MIMETYPE);
        file1.setIsInput(true);
        return this.getFiles();
    }

    @Override
    public void buildWorkflow() {

        /**
         * Steps for MiXCR
         */
        // provision files (2) -- clones.clns; clones.det.txt; both are text files
        
        Job parentJob = null;
        this.alignvdjcaFile =this.dataDir+  this.outputFilenamePrefix + ".alignments.vdjca";
        this.alignrescued1File = this.dataDir + this.outputFilenamePrefix + ".alignments_rescued_1.vdjca";
        this.alignrescued2File = this.dataDir + this.outputFilenamePrefix + ".alignments_rescued_2.vdjca";
        this.alignrescued2extendFile = this.dataDir + this.outputFilenamePrefix + ".alignments_rescued_2_extended.vdjca";
        this.cloneClnsFile = this.dataDir + this.outputFilenamePrefix + ".clones.clns";
        this.cloneDetTxtFile = this.dataDir + this.outputFilenamePrefix + ".clones.det.txt";
        

        Job vdjcgenes = alignVDJCgenes();
       
        Job Assembly1= contigAssembly1();
        Assembly1.addParent(vdjcgenes);
       
        Job Assembly2 = contigAssembly2();
        Assembly2.addParent(Assembly1);
      
        Job Extend = extendAlignment();
        Extend .addParent(Assembly2);
   
        Job AssembleClones = assembleClonotypes();
        AssembleClones.addParent(Extend);
        
        Job ExportClones = exportClonotypes();
        ExportClones.addParent(AssembleClones);

        // Provision clones.clns, clones.det.txt{{}}
        // Both files provisioned are txt files
        
        String clonesFile = this.dataDir + this.outputFilenamePrefix + "clones.clns";
        SqwFile clnsFile = createOutputFile(this.cloneClnsFile, TXT_METATYPE, this.manualOutput);
        clnsFile.getAnnotations().put("MiXCR_clones_clns", "MiXCR");
        ExportClones.addFile(clnsFile);
        
        String cloneTableFile = this.dataDir + this.outputFilenamePrefix + "clones.det.txt";
        SqwFile txtFile = createOutputFile( this.cloneDetTxtFile, TXT_METATYPE, this.manualOutput);
        txtFile.getAnnotations().put("MiXCR_clones_det_txt", "MiXCR");
        ExportClones.addFile(txtFile);  
    
    }
    
    
    
    private Job alignVDJCgenes() {
        Job vdjcgenes = getWorkflow().createBashJob("VDJCgenes");
        Command cmd = vdjcgenes.getCommand();
        cmd.addArgument(this.exports);
        cmd.addArgument(this.mixcr);
        cmd.addArgument("align -p rna-seq -s hsa -OallowPartialAlignments=true");
        cmd.addArgument(getFiles().get("read1").getProvisionedPath());
        cmd.addArgument(getFiles().get("read2").getProvisionedPath());
        cmd.addArgument(this.alignvdjcaFile);
        vdjcgenes.setMaxMemory(Integer.toString(mixcrMem * 1024));
        vdjcgenes.setQueue(getOptionalProperty("queue", ""));
        return vdjcgenes;
        
    }
    
      private Job contigAssembly1() {
        Job Assembly1 = getWorkflow().createBashJob("Assembly1");
        Command cmd = Assembly1.getCommand();
        cmd.addArgument(this.exports);
        cmd.addArgument(this.mixcr);
        cmd.addArgument("assemblePartial");
        cmd.addArgument(this.alignvdjcaFile);
        cmd.addArgument(this.alignrescued1File);
        Assembly1.setMaxMemory(Integer.toString(mixcrMem * 1024));
        Assembly1.setQueue(getOptionalProperty("queue", ""));
        return Assembly1;
    }
    
      
      private Job contigAssembly2() {
        Job Assembly2 = getWorkflow().createBashJob("Assembly2");
        Command cmd = Assembly2.getCommand();
        cmd.addArgument(this.exports);
        cmd.addArgument(this.mixcr);
        cmd.addArgument("assemblePartial");
        cmd.addArgument(this.alignrescued1File);
        cmd.addArgument(this.alignrescued2File);
        Assembly2.setMaxMemory(Integer.toString(mixcrMem * 1024));
        Assembly2.setQueue(getOptionalProperty("queue", ""));
        return Assembly2;
    }
    
      
      private Job extendAlignment() {
        Job Extend = getWorkflow().createBashJob("Extend");
        Command cmd = Extend.getCommand();
        cmd.addArgument(this.exports);
        cmd.addArgument(this.mixcr);
        cmd.addArgument("extendAlignments");
        cmd.addArgument(this.alignrescued2File);
        cmd.addArgument(this.alignrescued2extendFile);
        Extend.setMaxMemory(Integer.toString(mixcrMem * 1024));
        Extend.setQueue(getOptionalProperty("queue", ""));
        return Extend;
    }   
      
      
     private Job assembleClonotypes() {
        Job AssembleClones = getWorkflow().createBashJob("AssembleClones");
        Command cmd = AssembleClones.getCommand();
        cmd.addArgument(this.exports);
        cmd.addArgument(this.mixcr);
        cmd.addArgument("assemble");
        cmd.addArgument(this.alignrescued2extendFile);
        cmd.addArgument(this.cloneClnsFile);
        AssembleClones.setMaxMemory(Integer.toString(mixcrMem * 1024));
       AssembleClones.setQueue(getOptionalProperty("queue", ""));
        return AssembleClones;
    }   
     
     private Job exportClonotypes() {
        Job ExportClones = getWorkflow().createBashJob("ExportClones");
        Command cmd = ExportClones.getCommand();
        cmd.addArgument(this.exports);
        cmd.addArgument(this.mixcr);
        cmd.addArgument("exportClones");
        cmd.addArgument(this.cloneClnsFile);
        cmd.addArgument(this.cloneDetTxtFile);
        ExportClones.setMaxMemory(Integer.toString(mixcrMem * 1024));
        ExportClones.setQueue(getOptionalProperty("queue", ""));
        return ExportClones;
    }   
       
       
      
}
