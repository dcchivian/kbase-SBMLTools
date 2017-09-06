package pt.uminho.sysbio.biosynthframework.kbase;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.biojava.nbio.core.sequence.DNASequence;
import org.biojava.nbio.core.sequence.io.FastaReaderHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import datafileutil.DataFileUtilClient;
import genomeannotationapi.GenomeAnnotationAPIClient;
import genomeannotationapi.SaveGenomeResultV1;
import genomeannotationapi.SaveOneGenomeParamsV1;
import genomeproteomecomparison.GenomeProteomeComparisonClient;
import kbasegenomes.Feature;
import kbasegenomes.Genome;
import kbasereport.CreateParams;
import kbasereport.KBaseReportClient;
import kbasereport.Report;
import kbasereport.ReportInfo;
import kbasereport.WorkspaceObject;
import pt.uminho.sysbio.biosynthframework.genome.NAlignTool;
import pt.uminho.sysbio.biosynthframework.kbase.genome.AlignmentKernel;
import pt.uminho.sysbio.biosynthframework.kbase.genome.KbaseGenomeUtils;
import sbmltools.AutoPropagateModelParams;
import us.kbase.auth.AuthToken;
import us.kbase.common.service.JsonClientException;
import us.kbase.common.service.UnauthorizedException;
import us.kbase.workspace.WorkspaceClient;

public class AutoPropagateGenomeFacade {
  
  private static final Logger logger = LoggerFactory.getLogger(AutoPropagateGenomeFacade.class);
  
  private final NAlignTool alignTool;
//  /kb/module/data/
  public static String BLAST_DB_PATH = "/kb/module/data/blast_db.faa";
  
  private String genomeId;
  private String workspace;
  private final WorkspaceClient wsClient;
  private final DataFileUtilClient dfuClient;
  private final GenomeProteomeComparisonClient gpcClient;
  private final GenomeAnnotationAPIClient gaClient;
  private final KBaseReportClient kbrClient;
  
  public AutoPropagateGenomeFacade(AutoPropagateModelParams params, 
      WorkspaceClient wsClient,
      KBaseReportClient kbrClient,
      URL callbackUrl, AuthToken token) throws IOException, UnauthorizedException {
    this.alignTool = new NAlignTool(KbaseGenomeUtils.NUC44);
    this.genomeId = params.getGenomeId();
    this.workspace = params.getWorkspaceName();
    this.wsClient = wsClient;
    this.kbrClient = kbrClient;
    this.dfuClient = new DataFileUtilClient(callbackUrl, token);
    this.gpcClient = new GenomeProteomeComparisonClient(callbackUrl, token);
    this.gaClient = new GenomeAnnotationAPIClient(callbackUrl, token);
    this.dfuClient.setIsInsecureHttpConnectionAllowed(true);
    this.gpcClient.setIsInsecureHttpConnectionAllowed(true);
    this.gaClient.setIsInsecureHttpConnectionAllowed(true);
    
  }
  
  public ReportInfo run() {
    String out = "";
    Map<String, String> outputObjects = new HashMap<> ();
    try {
      InputStream is = new FileInputStream(BLAST_DB_PATH);
      Map<String, DNASequence> seqs = FastaReaderHelper.readFastaDNASequence(is);
      Map<String, Genome> seqsGenome = new HashMap<> ();
      for (String k : seqs.keySet()) {
        seqsGenome.put(k, null);
      }
      
      Pair<KBaseId, Object> data = KBaseIOUtils.getObject2(genomeId, workspace, null, wsClient);
      Genome genome = KBaseUtils.convert(data.getRight(), Genome.class);
      
      Feature poly = null;
      if (genome != null && 
          (poly = KbaseGenomeUtils.findRnaPolymeraseBetaUnit(genome)) != null) {
        AlignmentKernel ma = new AlignmentKernel(alignTool, 4);
        String dnaA = poly.getDnaSequence();
        
        for (String k : seqs.keySet()) {
          ma.jobs.add(new ImmutablePair<String, String>(dnaA, seqs.get(k).getSequenceAsString()));
        }
        
        ma.run();
        
        Map<Pair<String, String>, List<Object>> alignData = ma.getResults();
        for (Pair<String, String> k : alignData.keySet()) {
          out += "\n" + StringUtils.join(alignData.get(k), "; ");
          System.out.println(StringUtils.join(alignData.get(k), "; "));
        }
        
        SaveOneGenomeParamsV1 gparams = new SaveOneGenomeParamsV1().withData(genome).withWorkspace(workspace).withName("genome");
        SaveGenomeResultV1 gresults = gaClient.saveOneGenomeV1(gparams);
        String ref = KBaseIOUtils.getRefFromObjectInfo(gresults.getInfo());
        outputObjects.put(ref, "test save genome");
        //collect the best genomes
        
      } else {
        logger.warn("unable to find feature");
      }
    } catch (IOException | JsonClientException e) {
      out += e.getMessage();
      e.printStackTrace();
    }
    
    try {
      List<WorkspaceObject> wsObjects = new ArrayList<> ();
      for (String ref : outputObjects.keySet()) {
        String def = outputObjects.get(ref);
        wsObjects.add(new WorkspaceObject().withDescription(def)
                                           .withRef(ref));
      }
      
      final ReportInfo reportInfo = kbrClient.create(
          new CreateParams().withWorkspaceName(workspace)
          .withReport(new Report()
              .withObjectsCreated(wsObjects)
              .withTextMessage(out)));
      
      return reportInfo;
    } catch (IOException | JsonClientException e) {
      e.printStackTrace();
    }
    
    return null;
  }
}
