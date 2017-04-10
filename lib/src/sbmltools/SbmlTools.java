package sbmltools;

import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import assemblyutil.AssemblyUtilClient;
import assemblyutil.FastaAssemblyFile;
import assemblyutil.GetAssemblyParams;
import assemblyutil.SaveAssemblyParams;
import datafileutil.DataFileUtilClient;
import datafileutil.ObjectSaveData;
import datafileutil.SaveObjectsParams;
import pt.uminho.sysbio.biosynthframework.sbml.MessageType;
import pt.uminho.sysbio.biosynthframework.sbml.XmlMessage;
import pt.uminho.sysbio.biosynthframework.sbml.XmlObject;
import pt.uminho.sysbio.biosynthframework.sbml.XmlSbmlCompartment;
import pt.uminho.sysbio.biosynthframework.sbml.XmlSbmlModel;
import pt.uminho.sysbio.biosynthframework.sbml.XmlSbmlModelValidator;
import pt.uminho.sysbio.biosynthframework.sbml.XmlSbmlReaction;
import pt.uminho.sysbio.biosynthframework.sbml.XmlSbmlSpecie;
import pt.uminho.sysbio.biosynthframework.sbml.XmlStreamSbmlReader;
import us.kbase.auth.AuthToken;
import us.kbase.common.service.RpcContext;
import us.kbase.common.service.Tuple11;
import us.kbase.common.service.UObject;

public class SbmlTools {
  
  private static final Logger logger = LoggerFactory.getLogger(SbmlTools.class);
  
  public final AuthToken authPart;
  public final RpcContext jsonRpcContext;
  public final URL callbackURL;
  public final String workspace;
  
  public static void validateSbmlImportParams(SbmlImportParams params) {
    /* Step 1 - Parse/examine the parameters and catch any errors
     * It is important to check that parameters exist and are defined, and that nice error
     * messages are returned to users.  Parameter values go through basic validation when
     * defined in a Narrative App, but advanced users or other SDK developers can call
     * this function directly, so validation is still important.
     */
    final String workspaceName = params.getWorkspaceName();
    if (workspaceName == null || workspaceName.isEmpty()) {
        throw new IllegalArgumentException(
            "Parameter workspace_name is not set in input arguments");
    }
    final String assyRef = params.getAssemblyInputRef();
    if (assyRef == null || assyRef.isEmpty()) {
        throw new IllegalArgumentException(
                "Parameter assembly_input_ref is not set in input arguments");
    }
    if (params.getMinLength() == null) {
        throw new IllegalArgumentException(
                "Parameter min_length is not set in input arguments");
    }
    final long minLength = params.getMinLength();
    if (minLength < 0) {
        throw new IllegalArgumentException("min_length parameter cannot be negative (" +
                minLength + ")");
    }
  }
  
  public SbmlTools(String workspace, AuthToken authPart, URL callbackURL, RpcContext jsonRpcContext) {
    this.authPart = authPart;
    this.jsonRpcContext = jsonRpcContext;
    this.callbackURL = callbackURL;
    this.workspace = workspace;
  }
  
  public FBAModel convertModel(XmlSbmlModel xmodel, String modelId) {
    
    FBAModel model = new FBAModel();
    model.setId(modelId);
    model.setName(modelId); //get from xml if exists
    model.setGenomeRef("4345/2/1");
    model.setSource("");
    model.setSourceId("");
    model.setType("");
    model.setTemplateRef("");
    model.setGapfillings(new ArrayList<ModelGapfill> ());
    model.setGapgens(new ArrayList<ModelGapgen> ());
    model.setBiomasses(new ArrayList<Biomass> ());
    model.setModelcompounds(new ArrayList<ModelCompound> ());
    model.setModelcompartments(new ArrayList<ModelCompartment> ());
    model.setModelreactions(new ArrayList<ModelReaction> ());
    
    for (XmlSbmlCompartment xcmp : xmodel.getCompartments()) {
      String cmpEntry = xcmp.getAttributes().get("id");
      String cmpName = xcmp.getAttributes().get("name");
      if (cmpName == null || cmpName.trim().isEmpty()) {
        cmpName = "undefined";
      }
      ModelCompartment cmp = new ModelCompartment().withId(cmpEntry)
                                                   .withLabel(cmpName)
                                                   .withPH(7.3)
                                                   .withPotential(1.0)
                                                   .withCompartmentIndex(1L)
                                                   .withCompartmentRef("");
      model.getModelcompartments().add(cmp);
    }
    for (XmlSbmlSpecie xspi : xmodel.getSpecies()) {
      String spiEntry = xspi.getAttributes().get("id");
      String cmpEntry = xspi.getAttributes().get("compartment");
      String spiName = xspi.getAttributes().get("name");
      if (spiName == null || spiName.trim().isEmpty()) {
        spiName = "undefined";
      }
      ModelCompound cpd = new ModelCompound().withId(spiEntry)
                                             .withCompoundRef("cpd00001")
                                             .withModelcompartmentRef(cmpEntry)
                                             .withFormula("R")
                                             .withCharge(1.0)
                                             .withName(spiName);
      
      model.getModelcompounds().add(cpd);
    }
    for (XmlSbmlReaction xrxn : xmodel.getReactions()) {
      String rxnEntry = xrxn.getAttributes().get("id");
      String rxnName = xrxn.getAttributes().get("name");
      if (rxnName == null || rxnName.trim().isEmpty()) {
        rxnName = "undefined";
      }
      List<ModelReactionReagent> reagents = new ArrayList<> ();
      
      for (XmlObject o : xrxn.getListOfReactants()) {
        String species = o.getAttributes().get("species");
        double stoichiometry = Double.parseDouble(o.getAttributes().get("stoichiometry"));
        ModelReactionReagent r = new ModelReactionReagent()
            .withCoefficient(-1 * stoichiometry)
            .withModelcompoundRef(species);
        reagents.add(r);
      }
      for (XmlObject o : xrxn.getListOfProducts()) {
        String species = o.getAttributes().get("species");
        double stoichiometry = Double.parseDouble(o.getAttributes().get("stoichiometry"));
        ModelReactionReagent r = new ModelReactionReagent()
            .withCoefficient(stoichiometry)
            .withModelcompoundRef(species);
        reagents.add(r);
      }
      
      ModelReaction rxn = new ModelReaction().withId(rxnEntry)
                                             .withName(rxnName)
                                             .withDirection("=")
                                             .withProtons(1.0)
                                             .withReactionRef("rxn37841")
                                             .withModelReactionProteins(new ArrayList<ModelReactionProtein> ())
                                             .withProbability(1.0)
                                             .withModelcompartmentRef("");
      rxn.setModelReactionReagents(reagents);
      model.getModelreactions().add(rxn);
    }

    
    return model;
  }
  

  
  public static String getRefFromObjectInfo(Tuple11<Long, String, String, String, 
      Long, String, Long, String, String, Long, Map<String,String>> info) {
    return info.getE7() + "/" + info.getE1() + "/" + info.getE5();
  }
  
  public String saveData(String nameId, String dataType, Object o) throws Exception {
//    Object o = null;
//    String nameId = "";
//    String dataType = "";
    final DataFileUtilClient dfuClient = new DataFileUtilClient(callbackURL, authPart);
    dfuClient.setIsInsecureHttpConnectionAllowed(true);
    long wsId = dfuClient.wsNameToId(workspace);
    
    SaveObjectsParams params = new SaveObjectsParams()
        .withId(wsId)
        .withObjects(Arrays.asList(
            new ObjectSaveData().withName(nameId)
                                .withType(dataType)
                                .withData(new UObject(o))));
////    params.setId(wsId);
////    List<ObjectSaveData> saveData = new ArrayList<> ();
////    ObjectSaveData odata = new ObjectSaveData();
////    odata.set
////    
////    params.setObjects(saveData);
////    ;
    String ref = getRefFromObjectInfo(dfuClient.saveObjects(params).get(0));
    
    return ref;
  }
  
  public String filterContigs(String assyRef, Path scratch) throws Exception {
    /* Step 2 - Download the input data as a Fasta file
     * We can use the AssemblyUtils module to download a FASTA file from our Assembly data
     * object. The return object gives us the path to the file that was created.
     */
    System.out.println("Downloading assembly data as FASTA file.");
    final AssemblyUtilClient assyUtil = new AssemblyUtilClient(callbackURL, authPart);
    /* Normally this is bad practice, but the callback server (which runs on the same machine
     * as the docker container running the method) is http only
     * TODO Should allow the clients to not require a token, even for auth required methods,
     * since the callback server ignores the incoming token. No need to transmit the token
     * here.
     */
    assyUtil.setIsInsecureHttpConnectionAllowed(true);
    final FastaAssemblyFile fileobj = assyUtil.getAssemblyAsFasta(new GetAssemblyParams()
            .withRef(assyRef));
    
    /* Step 3 - Actually perform the filter operation, saving the good contigs to a new
     * fasta file.
     */
    final Path out = scratch.resolve("filtered.fasta");
//    long total = 0;
//    long remaining = 0;
//    try (final FASTAFileReader fastaRead = new FASTAFileReaderImpl(
//                new File(fileobj.getPath()));
//            final FASTAFileWriter fastaWrite = new FASTAFileWriter(out.toFile())) {
//        final FASTAElementIterator iter = fastaRead.getIterator();
//        while (iter.hasNext()) {
//            total++;
//            final FASTAElement fe = iter.next();
//            if (fe.getSequenceLength() >= minLength) {
//                remaining++;
//                fastaWrite.write(fe);
//            }
//        }
//    }
//    SaveObjectsParams saveObjectsParams = new SaveObjectsParams();
//    List<ObjectSaveData> objects = null;
//    ObjectSaveData odata = new ObjectSaveData();
//    odata.setType("kbase.asds");
//    UObject udata = new UObject("");
//    odata.setData(udata);
////    odata.setName(name);
////    saveObjectsParams.set
//    saveObjectsParams.setObjects(objects);
//    
//    DataFileUtilClient dataFileUtilClient = new DataFileUtilClient(callbackURL, authPart);
//    long wsid = dataFileUtilClient.wsNameToId(workspaceName);
//    dataFileUtilClient.saveObjects(saveObjectsParams);
    // Step 4 - Save the new Assembly back to the system
    final String newAssyRef = assyUtil.saveAssemblyFromFasta(new SaveAssemblyParams()
        .withAssemblyName(fileobj.getAssemblyName() + "_new")
        .withWorkspaceName(workspace)
        .withFile(new FastaAssemblyFile().withPath(fileobj.getPath())));
    
    return newAssyRef;
  }
  
  public static Map<String, MessageType> knownSpecieAttributes() {
    String[] attr = new String[] {
        "speciesType", "NONE",
        "charge", "NONE",
        "constant", "NONE",
        "metaid", "NONE",
        "hasOnlySubstanceUnits", "NONE",
        "sboTerm", "NONE",
        "boundaryCondition", "NONE",
        "chemicalFormula", "NONE",
        "initialAmount", "NONE",
        "name", "NONE",
        "compartment", "WARN",
        "id", "CRITICAL",
        "initialConcentration", "NONE",
    };
    Map<String, MessageType> fields = new HashMap<>();
    for (int i = 0; i < attr.length; i+=2) {
      fields.put(attr[i], MessageType.valueOf(attr[i + 1]));
    }
    return fields;
  }
  
  public static void xrxnAttributes(XmlSbmlModelValidator validator) {
    String[] xrxnAttr = new String[] {
        "upperFluxBound", "fast", "metaid", "reversible", "sboTerm", "name", "lowerFluxBound", "id"
    };
    String[] xrxnSpecieAttr = new String[] {
        "stoichiometry", "constant", "species", "metaid", "sboTerm"
    };
    validator.xrxnAttr.addAll(Arrays.asList(xrxnAttr));
    validator.xrxnStoichAttr.addAll(Arrays.asList(xrxnSpecieAttr));
  }
  
  public String importModel(SbmlImportParams params) {
    
    logger.info("import model ...");
    
    String reportText = "";
    try {
      URL url = new URL(params.getUrl());
      URLConnection connection = url.openConnection();
      
//      URL url = new URL(params.getUrl());
      XmlStreamSbmlReader reader = new XmlStreamSbmlReader(connection.getInputStream());
      XmlSbmlModel model = reader.parse();
//      msg = model.getAttributes().toString();
      XmlSbmlModelValidator validator = new XmlSbmlModelValidator(model, knownSpecieAttributes());
      xrxnAttributes(validator);
      
      List<XmlMessage> msgs = validator.validate();
      reportText = String.format("Species %d, Reactions %s, %s", model.getSpecies().size(), model.getReactions().size(), params.getUrl());
//      String txt = "";
      for (XmlMessage m : msgs) {
        reportText +="\n" + String.format("%s", m);
      }
//      reportText += SbmlTools.aaa(validator.validate());
//      reportText = String.format("Species %d, Reactions %s, %s", model.getSpecies().size(), model.getReactions().size(), params.getUrl());
      
      connection.getInputStream().close();
      
      FBAModel fbaModel = this.convertModel(model, "realmodel");
      this.saveData("realmodel", KBaseType.FBAModel.value(), fbaModel);
      
    } catch (Exception e) {
      e.printStackTrace();
      reportText = e.getMessage();
    }
    
    logger.info("import model [done]");
    
    return reportText;
  }
  
//  public static String aaa(List<XmlMessage> msgs) {
//    String txt = "";
//    for (XmlMessage m : msgs) {
//      txt +="\n" + String.format("%s", m);
//    }
//    
//    return txt;
//  }
}
