package pt.uminho.sysbio.biosynthframework.kbase;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pt.uminho.sysbio.biosynth.integration.BiodbService;
import pt.uminho.sysbio.biosynth.integration.io.dao.neo4j.MetaboliteMajorLabel;
import pt.uminho.sysbio.biosynth.integration.io.dao.neo4j.ReactionMajorLabel;
import pt.uminho.sysbio.biosynthframework.BHashMap;
import pt.uminho.sysbio.biosynthframework.BMap;
import pt.uminho.sysbio.biosynthframework.integration.ReferencePropagation;
import pt.uminho.sysbio.biosynthframework.integration.model.BiGG2AliasMultiMatchResolver;
import pt.uminho.sysbio.biosynthframework.integration.model.BoundaryConflictResolver;
import pt.uminho.sysbio.biosynthframework.integration.model.ConnectedComponents;
import pt.uminho.sysbio.biosynthframework.integration.model.ConnectedComponentsIntegrationEngine;
import pt.uminho.sysbio.biosynthframework.integration.model.DictionaryBaseIntegrationEngine;
import pt.uminho.sysbio.biosynthframework.integration.model.FirstDegreeReferences;
import pt.uminho.sysbio.biosynthframework.integration.model.IdBaseIntegrationEngine;
import pt.uminho.sysbio.biosynthframework.integration.model.IntegrationEngine;
import pt.uminho.sysbio.biosynthframework.integration.model.IntegrationMap;
import pt.uminho.sysbio.biosynthframework.integration.model.ModelSeedMultiMatchResolver;
import pt.uminho.sysbio.biosynthframework.integration.model.NameBaseIntegrationEngine;
import pt.uminho.sysbio.biosynthframework.integration.model.SearchTable;
import pt.uminho.sysbio.biosynthframework.integration.model.SearchTableFactory;
import pt.uminho.sysbio.biosynthframework.integration.model.SpecieIntegrationFacade;
import pt.uminho.sysbio.biosynthframework.integration.model.TrieIdBaseIntegrationEngine;
import pt.uminho.sysbio.biosynthframework.integration.model.XmlReferencesBaseIntegrationEngine;
import pt.uminho.sysbio.biosynthframework.io.FileImportKb;
import pt.uminho.sysbio.biosynthframework.report.IntegrationReportResultAdapter;
import pt.uminho.sysbio.biosynthframework.sbml.XmlSbmlModel;
import pt.uminho.sysbio.biosynthframework.sbml.XmlSbmlSpecie;
import pt.uminho.sysbio.biosynthframework.util.DataUtils;
import pt.uminho.sysbio.ext.MethoBuilder;
import pt.uminho.sysbio.ext.ReactionIntegration;

public class KBaseModelSeedIntegration {
  
  public static class KBaseMappingResult {
    Map<String, Map<MetaboliteMajorLabel, String>> species = new HashMap<> ();
    Map<String, Map<ReactionMajorLabel, String>> reactions = new HashMap<> ();
  }
  
  private static final Logger logger = LoggerFactory.getLogger(KBaseModelSeedIntegration.class);
  
  public String biodbDataPath;
  public String curationFilePath;
  public Map<String, String> spiToModelSeedReference = new HashMap<> ();
  public Map<String, String> rxnToModelSeedReference = new HashMap<> ();
  
//  public BiodbService biodbService = null;
  public SearchTable<MetaboliteMajorLabel, String> searchTable = null;
  public ConnectedComponents<String> ccs = null;
  public MethoBuilder builder;
  
  private final KBaseBiodbContainer biodbContainer;
  
  public KBaseModelSeedIntegration(String biodbDataPath, String curationPath, final KBaseBiodbContainer biodbContainer) {
    this.biodbDataPath = biodbDataPath;
    this.curationFilePath = curationPath;
    this.biodbContainer = biodbContainer;
    // /data/biobase/
    FileImportKb.EXPORT_PATH = biodbDataPath;
    setup();
  }
  
  public void setup() {
    BiodbService biodbService = biodbContainer.biodbService;
    
    searchTable = new SearchTableFactory(biodbService)
        .withDatabase(MetaboliteMajorLabel.BiGG)
        .withDatabase(MetaboliteMajorLabel.BiGG2)
        .withDatabase(MetaboliteMajorLabel.ModelSeed)
        .withDatabase(MetaboliteMajorLabel.LigandCompound)
        .build();
    ccs = KBaseModelSeedIntegration.loadConnectedComponents(curationFilePath);
    
    builder = new MethoBuilder(biodbService);
  }
  
  public static ConnectedComponents<String> loadConnectedComponents(String path) {
    ConnectedComponents<String> ccs = new ConnectedComponents<>();
    InputStream is = null;
    try {
      is = new FileInputStream(path);
      for (String l : IOUtils.readLines(is)) {
        String[] s = l.split("\t");
        ccs.add(new HashSet<> (Arrays.asList(s)));
      }
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      IOUtils.closeQuietly(is);
    }

    
    return ccs;
  }
  
  public KBaseMappingResult generateDatabaseReferences(
      XmlSbmlModel xmodel, 
      String modelEntry, 
      IntegrationReportResultAdapter resultAdapter, 
      ReactionIntegration reactionIntegration) {
    BiodbService biodbService = biodbContainer.biodbService;
    
//    BiodbService service = new File
    
    SpecieIntegrationFacade integration = new SpecieIntegrationFacade();
    
    BMap<String, String> spiEntryToName = new BHashMap<> ();
    //names with split _
    BMap<String, String> spiEntryToName2 = new BHashMap<> ();
    Set<String> spiEntrySet = new HashSet<> ();
    BoundaryConflictResolver r1 = new BoundaryConflictResolver();
    ModelSeedMultiMatchResolver r2 = new ModelSeedMultiMatchResolver();
    BiGG2AliasMultiMatchResolver r3 = new BiGG2AliasMultiMatchResolver(biodbService);
    integration.matchResolver.put(MetaboliteMajorLabel.ModelSeed, r2);
    integration.matchResolver.put(MetaboliteMajorLabel.Seed, r2);
    integration.matchResolver.put(MetaboliteMajorLabel.BiGG2, r3);
    integration.specieConflictResolve = r1;
    Map<String, String> spiToCmp = new HashMap<>();
    for (XmlSbmlSpecie xspi : xmodel.getSpecies()) {
//      String id = xspi.getAttributes().get("id");
      String sname = xspi.getAttributes().get("name");
      String spiEntry = xspi.getAttributes().get("id");
      String cmpId = xspi.getAttributes().get("compartment");
      String bcondition = xspi.getAttributes().get("boundaryCondition");
      
      try {
        if (spiEntry != null && bcondition != null && Boolean.parseBoolean(bcondition)) {
          r1.boundary.add(spiEntry);
        }
      } catch (Exception e) {
        logger.warn("{}", e.getMessage());
      }
      integration.addSpecie(spiEntry, cmpId);
      spiToCmp.put(spiEntry, cmpId);
//      integration.patterns.put(, null);
//      integration.spiToCompartment.put(
//          xspi.getAttributes().get("id"), 
//          xspi.getAttributes().get("compartment"));

      if (!DataUtils.empty(spiEntry)) {
        spiEntrySet.add(spiEntry);
      }
      
      if (!DataUtils.empty(sname)) {
        String t = sname.trim().toLowerCase();

        int sepIndex = t.lastIndexOf('_');
        if (sepIndex > -1) {
          String part1 = t.substring(0, sepIndex);
          String part2 = t.substring(sepIndex + 1);
          if (!DataUtils.empty(part1)) {
//            System.out.println(spiEntry + " " + part1.trim());
            spiEntryToName2.put(spiEntry, part1.trim());
          }
        }
        
        if (t.startsWith("m_")) {
          if (t.contains("_")) {
            t = t.substring(0, t.lastIndexOf('_'));
          }
          if (t.startsWith("m_")) {
            t = t.substring(2);
          }
          t = t.replaceAll("_", "-");
        }
        spiEntryToName.put(spiEntry, t);
      }
    }
    
    
    
    integration.generatePatterns();
    
    // /data/biobase/export
    IdBaseIntegrationEngine be1 = builder.buildIdBaseIntegrationEngine();
    TrieIdBaseIntegrationEngine be2 = builder.buildTrieIdBaseIntegrationEngine();
    be2.ids = new HashSet<> (spiEntrySet);
    NameBaseIntegrationEngine be3 = builder.buildNameBaseIntegrationEngine();
    NameBaseIntegrationEngine be3split = builder.buildNameBaseIntegrationEngine();
    XmlReferencesBaseIntegrationEngine be0 = builder.buildXmlReferencesBaseIntegrationEngine();
    be0.xmlSpecies = new ArrayList<> (xmodel.getSpecies());
    DictionaryBaseIntegrationEngine beX = builder.buildDictionaryBaseIntegrationEngine();
    beX.ids = new HashSet<> (spiEntrySet);
    IntegrationEngine ie1 = new ConnectedComponentsIntegrationEngine(ccs);
    IntegrationEngine ie2 = new FirstDegreeReferences(biodbService);
    
    be3.spiEntryToName = spiEntryToName;
    be3split.spiEntryToName = spiEntryToName2;
    be1.patterns = integration.getPatterns();
    integration.baseEngines.put("dict", beX);
    integration.baseEngines.put("refs", be0);
    integration.baseEngines.put("pattern", be1);
    integration.baseEngines.put("trie", be2);
    integration.baseEngines.put("name", be3);
    integration.baseEngines.put("nameSplit", be3split);
    List<IntegrationEngine> l1 = new ArrayList<> ();
    l1.add(ie1);
    l1.add(ie2);
//    
//    List<IntegrationEngine> l2 = new ArrayList<> ();
//    l2.add(ie2);
//    l2.add(new DummyIntegrationEngine());
    integration.engines.add(l1);
//    integration.engines.add(l2);
    integration.run();
    
    
    Map<String, Map<MetaboliteMajorLabel, String>> imap = integration.build();
    
    
//    for (String k : imap.keySet()) {
//      System.out.println(k + "\t" + imap.get(k));
//    }
    
//    System.out.println(imap);
    imap = integration.clean;
    
    ReferencePropagation propagation = new ReferencePropagation();
    for (String k : imap.keySet()) {
      Map<MetaboliteMajorLabel, String> references = imap.get(k);
      for (MetaboliteMajorLabel db : references.keySet()) {
        propagation.addReference(k, spiToCmp.get(k), db, references.get(db));
      }
    }
    
    integration.status2(imap);
    
    IntegrationMap<String, MetaboliteMajorLabel> pmap = 
        propagation.propagate(true, ccs);
    
    for (String k : pmap.keySet()) {
      Map<MetaboliteMajorLabel, Set<String>> references = pmap.get(k);
      for (MetaboliteMajorLabel database : references.keySet()) {
        for (String dbEntry : references.get(database)) {
          imap.get(k).put(database, dbEntry);
        }
      }
    }
    
    integration.status2(imap);
    
//    System.out.println(imap);
    if (resultAdapter != null) {
//      System.out.println(modelEntry + " -> " + integration.clean.size());
      resultAdapter.fillSpeciesIntegrationData(integration);
    }
    
    for (String id : imap.keySet()) {
      String ref = imap.get(id).get(MetaboliteMajorLabel.ModelSeed);
      if (ref != null) {
        spiToModelSeedReference.put(id, ref);
      }
    }
    
    
    
    reactionIntegration.imap.clear();
    
    reactionIntegration.integrate(ReactionMajorLabel.LigandReaction, xmodel, integration.clean);
    reactionIntegration.integrate(ReactionMajorLabel.Seed, xmodel, integration.clean);
    reactionIntegration.integrate(ReactionMajorLabel.BiGG, xmodel, integration.clean);
    reactionIntegration.integrate(ReactionMajorLabel.MetaCyc, xmodel, integration.clean);
    reactionIntegration.integrate(ReactionMajorLabel.ModelSeedReaction, xmodel, integration.clean);

    resultAdapter.fillReactionIntegrationData(reactionIntegration.imap);
    
    KBaseMappingResult result = new KBaseMappingResult();
    result.species = imap;
    result.reactions = reactionIntegration.imap;
    
    for (String id : reactionIntegration.imap.keySet()) {
      String ref = reactionIntegration.imap.get(id).get(ReactionMajorLabel.ModelSeedReaction);
      if (ref != null) {
        rxnToModelSeedReference.put(id, ref);
      }
    }
    
    return result;

//    ObjectMapper om = new ObjectMapper();
//    System.out.println(om.writeValueAsString(fbaModel));
//    System.out.println("model" + fbaModel);
  }
}
