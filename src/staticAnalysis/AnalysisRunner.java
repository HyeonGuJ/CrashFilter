package staticAnalysis;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import com.google.security.zynamics.binnavi.API.disassembly.Address;
import com.google.security.zynamics.binnavi.API.disassembly.CouldntLoadDataException;
import com.google.security.zynamics.binnavi.API.disassembly.CouldntSaveDataException;
import com.google.security.zynamics.binnavi.API.disassembly.Function;
import com.google.security.zynamics.binnavi.API.disassembly.FunctionEdge;
import com.google.security.zynamics.binnavi.API.disassembly.Instruction;
import com.google.security.zynamics.binnavi.API.disassembly.Module;
import com.google.security.zynamics.binnavi.API.disassembly.ModuleHelpers;
import com.google.security.zynamics.binnavi.API.disassembly.PartialLoadException;
import com.google.security.zynamics.binnavi.API.disassembly.View;
import com.google.security.zynamics.binnavi.API.gui.LogConsole;
import com.google.security.zynamics.binnavi.API.helpers.MessageBox;
import com.google.security.zynamics.binnavi.API.plugins.PluginInterface;
import com.google.security.zynamics.binnavi.API.reil.InternalTranslationException;
import com.google.security.zynamics.binnavi.API.reil.ReilBlock;
import com.google.security.zynamics.binnavi.API.reil.ReilFunction;
import com.google.security.zynamics.binnavi.API.reil.ReilHelpers;
import com.google.security.zynamics.binnavi.API.reil.ReilInstruction;
import com.google.security.zynamics.binnavi.API.reil.mono.ILatticeGraph;
import com.google.security.zynamics.binnavi.API.reil.mono.IStateVector;
import com.google.security.zynamics.binnavi.API.reil.mono.InstructionGraph;
import com.google.security.zynamics.binnavi.API.reil.mono.InstructionGraphNode;

import crashfilter.va.MLocAnalysis.MLocAnalysis;
import crashfilter.va.MLocAnalysis.MLocLatticeElement;
import crashfilter.va.memlocations.MLocException;
import data.CountInstructionHashMap;
import data.CrashPoint;
import data.ReilInstructionResolve;
import helper.ArgumentScanner;
import helper.CallStackCleaner;
import helper.CrashFileScanner;
import helper.CrashSourceAdder;
import helper.Dangerousness;
import helper.HeapChecker;
import helper.InterProcedureMode;
import helper.VariableFinder;
import staticAnalysis.RDAnalysis.RDLatticeElement;
import view.ExploitPathView;

public class AnalysisRunner {
    final private File crashFolder;
    final private PluginInterface m_pluginInterface;
    final private Module module;

    private Map<Long, Dangerousness> crashFilteringResult = new HashMap<>();

    private String crashAddr = "";

    private boolean singleCrashCheck = false;
    private boolean memoryAnalysisCheck = false;
    private boolean crashSrcAnalysisCheck = false;
    private boolean interProcedureAnalysisCheck = false;
    private boolean callCountCheck = false;

    private Map<Long, Dangerousness> functionDangerousnessDynamicTable = new HashMap<>();

    private double analysisVersion = 0;

    private int e_path_cnt = 0;
    private int pe_path_cnt = 0;
    private int e_cnt = 0;
    private int pe_cnt = 0;
    private int ne_cnt = 0;
    private int ne_call_cnt = 0;
    private int totalTime = 0;
    private int escapableAnalysisCount = 0;
    private int e_call_cnt = 0;

    public AnalysisRunner(PluginInterface m_plugin, File crachFolder, Module module, String crashAddr, int optionCode,
            int interProcedureDepth) {
        this.module = module;
        System.out.println(module.getFilebase());
        this.m_pluginInterface = m_plugin;
        this.crashFolder = crachFolder;
        this.crashAddr = crashAddr;
        decodeOptionCode(optionCode);
    }

    private void decodeOptionCode(int code) {
        singleCrashCheck = ((code & 0x1) == 0x1);
        memoryAnalysisCheck = ((code & 0x10) == 0x10);
        crashSrcAnalysisCheck = ((code & 0x100) == 0x100);
        callCountCheck = ((code & 0x10000) == 0x10000);
        interProcedureAnalysisCheck = ((code & 0x1000) == 0x1000);

        System.out.println("singleCrashCheck  :" + singleCrashCheck);
        System.out.println("crashSrcAnalysisCheck  :" + crashSrcAnalysisCheck);
        System.out.println("memoryAnalysisCheck  :" + memoryAnalysisCheck);
        System.out.println("interProcedureAnalysisCheck  :" + interProcedureAnalysisCheck);

        analysisVersion = getAnalysisVersion();

    }

    private double getAnalysisVersion() {

        if (crashSrcAnalysisCheck && memoryAnalysisCheck && interProcedureAnalysisCheck)
            return 1.4;

        if (crashSrcAnalysisCheck && interProcedureAnalysisCheck)
            return 1.32;
        if (crashSrcAnalysisCheck && memoryAnalysisCheck) {
            if (callCountCheck) {
                return 1.31;
            }
            return 1.30;
        }
        if (crashSrcAnalysisCheck)
            return 1.2;

        return 1.0;

    }

    void runAnalysis(InterProcedureMode interProcedureAnalysisMode) throws MLocException, InternalTranslationException {

        Map<Long, CrashPoint> crashPointToFuncAddr = findFunctionFromCrashPointAddr();
        CountInstructionHashMap cihm = new CountInstructionHashMap();

        for (Long crashPointAddress : crashPointToFuncAddr.keySet()) {

            Dangerousness dangerousness = runSingleCrash(interProcedureAnalysisMode, crashPointToFuncAddr, cihm,
                    crashPointAddress);
            crashFilteringResult.put(crashPointAddress, dangerousness);

        }

        for (Long crashPointAddress : crashPointToFuncAddr.keySet()) {

            Dangerousness dangerousness = runSingleCrash(interProcedureAnalysisMode, crashPointToFuncAddr, cihm,
                    crashPointAddress);
            crashFilteringResult.put(crashPointAddress, dangerousness);

        }

        LogConsole.log(cihm.toString());

        countExploitableCrash();
        printExploitableCount(e_cnt, pe_cnt, ne_cnt);
        printExploitablePathCount();
        // System.out.println("call Count : " + callCounter);

        LogConsole.log("total time : " + totalTime + "\n");
    }

    private Dangerousness runSingleCrash(InterProcedureMode interProcedureAnalysisMode,
            Map<Long, CrashPoint> crashPointToFuncAddr, CountInstructionHashMap cihm, Long crashPointAddress)
                    throws MLocException, InternalTranslationException {

        
        
        
        
        Dangerousness dagnerousness = Dangerousness.NE;
        ILatticeGraph<InstructionGraphNode> graph = null;
        IStateVector<InstructionGraphNode, RDLatticeElement> RDResult = null;
        IStateVector<InstructionGraphNode, MLocLatticeElement> mLocResult = null;

        LogConsole.log("Parsing File Number : " + crashPointToFuncAddr.size() + "\n\n");

        int viewIndex = 0;

        crashAddr = Long.toHexString(crashPointAddress);

        LogConsole.log("now analyzing : " + Long.toHexString(crashPointAddress) + "\n");
        System.out.println("\n now analyzing : " + Long.toHexString(crashPointAddress));
        long before = System.currentTimeMillis();

        ReilFunction curReilFunc = null;
        List<ReilInstruction> crashReilInst = new ArrayList<ReilInstruction>();
        List<InstructionGraphNode> taintSourceInstructionGraphNodes = new ArrayList<InstructionGraphNode>();

        Function curFunc = ModuleHelpers.getFunction(module, crashPointToFuncAddr.get(crashPointAddress).getFuncAddr());

        Instruction crashInst = checkFunctionLoaded(cihm, crashPointAddress, curFunc);
        graph = translateFunc2ReilFunc(graph, curReilFunc, crashReilInst, taintSourceInstructionGraphNodes, curFunc,
                crashInst);

        

        
        
        /************* Analysis Option Check ********************/
        if (memoryAnalysisCheck) {
            mLocResult = memoryAnalysis(graph, curFunc, mLocResult);
        }

        if (interProcedureAnalysisCheck && interProcedureAnalysisMode == InterProcedureMode.FUNCTIONAnalysis) {

            Set<Map<Address, String>> scannedArgument = ArgumentScanner.ArgumentScan(curFunc);

            System.out.println("0x" + curFunc.getAddress().toHexString());
            // ArgumentScanner.print(scannedArgument);
        }

        System.out.println("== start EEEEEEEEEEEEEEE ==");
        VariableFinder vf = new VariableFinder(module, curFunc);
        RDAnalysis rda = new RDAnalysis(graph, crashPointAddress, vf);

        RDResult = rda.runRDAnalysis(interProcedureAnalysisMode);
        // rda.printRD(RDResult);
        LogConsole.log("== end rd analysis ==\n");

        LogConsole.log("==start du analysis  1==\n");
        DefUseChain du = new DefUseChain(RDResult, graph, crashPointAddress, crashSrcAnalysisCheck);
        du.setMemoryResult(mLocResult);
        du.defUseChaining();
        LogConsole.log("== end DU analysis ==\n");

        crashSrcAnalysis(interProcedureAnalysisMode, crashPointAddress, graph, taintSourceInstructionGraphNodes, vf,
                du);

        ExploitableAnalysis exploitableAnalysis = new ExploitableAnalysis(du.getDuGraphs(), curFunc, crashPointAddress);

        switch (interProcedureAnalysisMode) {
        case NORMAL:

            if (exploitableAnalysis.isTaintSink()) {
                makeView(crashPointToFuncAddr, viewIndex, crashPointAddress, curFunc, exploitableAnalysis);
                dagnerousness = exploitableAnalysis.getDangerousness();
                e_call_cnt++;
            }

            if (needToInterProcedureAnalysis(dagnerousness)) {
                Dangerousness dagnerousness_inter = interProcedureAnalysis(cihm, graph, curFunc, exploitableAnalysis);
                dagnerousness = getMoreDangerousOne(dagnerousness, dagnerousness_inter);
                ne_call_cnt++;
            } else {
                if (needToCountFunctionCall(dagnerousness)) {

                    if (hasFunctionCalls(graph, curFunc)) {
                        dagnerousness = Dangerousness.PE;
                        ne_call_cnt++;
                    }
                }
            }

            break;

        case FUNCTIONAnalysis:
            List<Function> calleeFunction = getCallee(graph, curFunc);
            Dangerousness dagnerousness_global = Dangerousness.NE;
            dagnerousness_global = glovalVariableAnalysis(curFunc, calleeFunction, crashPointToFuncAddr);
            
            ReturnValueAnalysis returnValueAnalysis = new ReturnValueAnalysis(du.getDuGraphs(), curFunc,
                    crashFilteringResult, RDResult, graph);

            if (returnValueAnalysis.isTaintSink() || exploitableAnalysis.isTaintSink()) {
                dagnerousness = getMoreDangerousOne(returnValueAnalysis.getDnagerousness(),exploitableAnalysis.getDangerousness());
                
                makeView(crashPointToFuncAddr, viewIndex, crashPointAddress, curFunc, returnValueAnalysis);
            }
            dagnerousness = getMoreDangerousOne (dagnerousness,dagnerousness_global);
            

            Set<String> usedGlobalVariables = new GlobalVariableAnalysis(module, curFunc).getUsedGlobalVariables();
            System.out.println("global test");
            for(String str : usedGlobalVariables)
            {
                System.out.println(str);
            }

            break;
        case GVAnalysis:
            break;
        default:
            break;
        }

        // add escape analysis -->
        // src : returned value
        // sink : return value

        dagnerousness = escapableAnalysis(dagnerousness, graph, RDResult, curFunc, du);

        e_path_cnt += exploitableAnalysis.getTotal_e_count();
        pe_path_cnt += exploitableAnalysis.getTotal_pe_count();

        LogConsole.log("==========end Exploitable analysis ===========\n");

        long after = System.currentTimeMillis();
        long processingTime = after - before;

        LogConsole.log(curFunc.getName() + "-- time : " + processingTime + "\n\n");
        totalTime += processingTime;
        viewIndex++;

        functionDangerousnessDynamicTable.put(crashPointAddress, dagnerousness);
        return dagnerousness;
    }

    private Dangerousness escapableAnalysis(Dangerousness dagnerousness, ILatticeGraph<InstructionGraphNode> graph,
            IStateVector<InstructionGraphNode, RDLatticeElement> RDResult, Function curFunc, DefUseChain du) {
        ReturnValueAnalysis escapableAnalysis = new ReturnValueAnalysis(du.getDuGraphs(), curFunc, crashFilteringResult,
                RDResult, graph);
        if (escapableAnalysis.isTaintSink()) {
            dagnerousness = getMoreDangerousOne(dagnerousness, Dangerousness.PE);
            if (dagnerousness.getDangerous() > Dangerousness.E.getDangerous()) {
                escapableAnalysisCount++;
            }
        }
        return dagnerousness;
    }

    private boolean needToCountFunctionCall(Dangerousness dagnerousness) {
        if (callCountCheck) {
            return (dagnerousness == Dangerousness.PE || dagnerousness == Dangerousness.NE)
                    && !interProcedureAnalysisCheck;
        }
        return false;
    }

    private boolean needToInterProcedureAnalysis(Dangerousness dagnerousness) {

        if (interProcedureAnalysisCheck) {
            return (dagnerousness == Dangerousness.NE) || (dagnerousness == Dangerousness.PE);
        }

        return false;
    }

    private void crashSrcAnalysis(InterProcedureMode interProcedureAnalysisMode, Long crashPointAddress,
            ILatticeGraph<InstructionGraphNode> graph, List<InstructionGraphNode> taintSourceInstructionGraphNodes,
            VariableFinder vf, DefUseChain du) {
        // crashSrcAnalysis
        if (crashSrcAnalysisCheck) {
            if (interProcedureAnalysisMode != InterProcedureMode.NORMAL) {
                taintSourceInstructionGraphNodes.clear();
            }
            taintSourceInstructionGraphNodes
                    .addAll(CrashSourceAdder.getInstructions(graph, crashPointAddress, interProcedureAnalysisMode, vf));
        }
        for (InstructionGraphNode taintSourceInstructionGraphNode : taintSourceInstructionGraphNodes) {
            du.createDefUseGraph(taintSourceInstructionGraphNode);
        }
    }

    private Dangerousness interProcedureAnalysis(CountInstructionHashMap cihm,
            ILatticeGraph<InstructionGraphNode> graph, Function curFunc, ExploitableAnalysis exploitableAnalysis)
                    throws MLocException, InternalTranslationException {

        Dangerousness dagnerousness = exploitableAnalysis.getDangerousness();

        if (dontHaveToInterProcedureAnalysis(graph, curFunc, dagnerousness)) {
            return dagnerousness;
        }

        List<Function> calleeFunction = getCallee(graph, curFunc);
        Map<Long, CrashPoint> crashPointToFuncAddr = new HashMap<>();

        Dangerousness dangerousness_g = Dangerousness.NE;
        dangerousness_g = glovalVariableAnalysis(curFunc, calleeFunction, crashPointToFuncAddr);

        Dangerousness dangerousness_f = Dangerousness.NE;

        for (Function callee : calleeFunction) {

            String calleeAddressHexString = "0x" + callee.getAddress().toHexString();
            Map<Long, CrashPoint> parseCrashFiles = CrashFileScanner.parseCrashFiles(null, module,
                    calleeAddressHexString, true);
            crashPointToFuncAddr.putAll(parseCrashFiles);

            Dangerousness dangerousness_f_temp = getCalleesDangerousness(cihm, crashPointToFuncAddr, callee);

            functionDangerousnessDynamicTable.put(callee.getAddress().toLong(), dangerousness_f);
            dangerousness_f = getMoreDangerousOne(dangerousness_f_temp, dangerousness_f);

        }

        dagnerousness = getMoreDangerousOne(dangerousness_f, dangerousness_g);
        return dagnerousness;
    }

    private Dangerousness getCalleesDangerousness(CountInstructionHashMap cihm,
            Map<Long, CrashPoint> crashPointToFuncAddr, Function callee)
                    throws MLocException, InternalTranslationException {

        Dangerousness dangerousness_f;
        dangerousness_f = runSingleCrash(InterProcedureMode.FUNCTIONAnalysis, crashPointToFuncAddr, cihm,
                callee.getAddress().toLong());

        return dangerousness_f;
    }

    private Dangerousness glovalVariableAnalysis(Function curFunc, List<Function> calleeFunctions,
            Map<Long, CrashPoint> crashPointToFuncAddr) {

        GlobalVariableAnalysis globalVariableAnalysis = new GlobalVariableAnalysis(module, curFunc);
        if (globalVariableAnalysis.dontUseGlobalVariable()) {
            return Dangerousness.NE;
        } else {
            for (Function calleeFunction : calleeFunctions) {

                // Function curFunc = ModuleHelpers.getFunction(module,
                // crashPointToFuncAddr.get(crashPointAddress).getFuncAddr());
                GlobalVariableAnalysis globalVariableAnalysis_callee = new GlobalVariableAnalysis(module,
                        calleeFunction);
                if (globalVariableAnalysis.hasSameGlobalVaraible(globalVariableAnalysis_callee)) {
                    return Dangerousness.PE;
                }
            }
        }
        return Dangerousness.NE;
    }

    private boolean dontHaveToInterProcedureAnalysis(ILatticeGraph<InstructionGraphNode> graph, Function curFunc,
            Dangerousness dagnerousness) {
        return !hasFunctionCalls(graph, curFunc);
    }

    private Dangerousness getMoreDangerousOne(Dangerousness dagnerousness_1, Dangerousness dagnerousness_2) {
        return dagnerousness_1.getDangerous() > dagnerousness_2.getDangerous() ? dagnerousness_1 : dagnerousness_2;
    }

    private List<Function> getCallee(ILatticeGraph<InstructionGraphNode> graph, Function curFunc) {

        List<Function> callees = new ArrayList<Function>();

        List<FunctionEdge> edges = module.getCallgraph().getEdges();

        for (FunctionEdge edge : edges) {
            if (edge.getSource().getFunction().equals(curFunc)) {
                callees.add(edge.getTarget().getFunction());
            }
        }

        return callees;
    }

    private void makeView(Map<Long, CrashPoint> crashPointToFuncAddr, int viewIndex, Long crashPointAddress,
            Function curFunc, TaintSink taintSink) {
        Map<Instruction, List<Instruction>> exploitPaths = taintSink.getExploitArmPaths();

        if (!exploitPaths.isEmpty()) {
            View view = module.createView(curFunc.getName(),
                    String.format("[%d], %s", viewIndex, crashPointToFuncAddr.get(crashPointAddress).getfileName()));
            try {
                view.load();
            } catch (CouldntLoadDataException e1) {
                e1.printStackTrace();
            } catch (PartialLoadException e1) {
                e1.printStackTrace();
            }
            ExploitPathView exploitView = new ExploitPathView(exploitPaths);
            exploitView.createExploitPathView(view);
            try {
                view.save();
            } catch (CouldntSaveDataException e1) {
                e1.printStackTrace();
            }

        }
    }

    private ILatticeGraph<InstructionGraphNode> translateFunc2ReilFunc(ILatticeGraph<InstructionGraphNode> graph,
            ReilFunction curReilFunc, List<ReilInstruction> crashReilInst,
            List<InstructionGraphNode> crashInstructionGraphNode, Function curFunc, Instruction crashInst) {
        try {
            curReilFunc = curFunc.getReilCode();
        } catch (InternalTranslationException translationException) {
            MessageBox.showException(m_pluginInterface.getMainWindow().getFrame(), null,
                    "Translation ERROR : TO REIL Graph");
        }

        for (ReilBlock block : curReilFunc.getGraph().getNodes()) {

            for (ReilInstruction inst : block.getInstructions()) {

                if (ReilHelpers.toNativeAddress(inst.getAddress()).equals(crashInst.getAddress())) {
                    crashReilInst.add(inst);
                    // LogConsole.log(inst.toString()+"\n");
                }
            }
        }

        if (curReilFunc != null) {
            graph = InstructionGraph.create(curReilFunc.getGraph()); // API's
                                                                     // structure

            for (InstructionGraphNode instGraphNode : graph.getNodes()) {
                for (ReilInstruction reilInst : crashReilInst) {
                    if (instGraphNode.getInstruction().getAddress().equals(reilInst.getAddress())) {
                        crashInstructionGraphNode.add(instGraphNode);
                        // LogConsole.log(instGraphNode.getInstruction().toString()+"\n");
                        break;
                    }
                }
            }
        }
        return graph;
    }

    private Instruction checkFunctionLoaded(CountInstructionHashMap cihm, Long crashPointAddress, Function curFunc) {
        try {
            if (!curFunc.isLoaded()) {
                curFunc.load();
            }
        } catch (CouldntLoadDataException e1) {
            e1.printStackTrace();
        } catch (Exception e1) {
            System.out.println("dubugging ");
        }

        Instruction crashInst = ReilInstructionResolve.findNativeInstruction(curFunc, crashPointAddress);

        if (curFunc == null) {
            System.out.println("function null!!!!");
        }
        if (crashInst == null) {
            System.out.println("instruction null!!!!");
        } else {
            LogConsole.log(crashInst.toString() + "\n");
            StringTokenizer st = new StringTokenizer(crashInst.toString(), " ");
            st.nextToken();
            cihm.put(st.nextToken(), 1);
        }
        return crashInst;
    }

    private void countExploitableCrash() {
        e_cnt = 0;
        pe_cnt = 0;
        ne_cnt = 0;

        FileOutputStream output;
        try {
            String moduleName = module.getName();
            output = new FileOutputStream("d:/" + moduleName + "_" + analysisVersion + ".txt");

            for (Long addr : crashFilteringResult.keySet()) {
                String outputStr = "0x" + Long.toHexString(addr) + "  :  " + crashFilteringResult.get(addr) + "\r\n";
                System.out.print(outputStr);
                output.write(outputStr.getBytes());
                if (crashFilteringResult.get(addr).equals(Dangerousness.E))
                    e_cnt++;
                if (crashFilteringResult.get(addr).equals(Dangerousness.PE))
                    pe_cnt++;
                if (crashFilteringResult.get(addr).equals(Dangerousness.NE))
                    ne_cnt++;

            }

            String outputString = "\r\n" + "E : " + e_cnt + "\r\n";
            outputString += "PE : " + pe_cnt + "\r\n";
            outputString += "NE : " + ne_cnt + "\r\n";

            if (!interProcedureAnalysisCheck) {
                outputString += "\r\ncall : " + ne_call_cnt + "(ne --> pe)" + "\r\n";
            }

            outputString += "escapable: " + escapableAnalysisCount + "\r\n";
            outputString = concatPathCountString(outputString);

            outputString += "total time : " + totalTime + "\r\n";

            output.write(outputString.getBytes());

            output.close();

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private String concatPathCountString(String outputString) {
        outputString += "+\r\npath count : \r\n";
        outputString += "E: " + e_path_cnt + "\r\n";
        outputString += "E: " + pe_path_cnt + "\r\n";
        return outputString;
    }

    private void printExploitablePathCount() {
        System.out.println("count of E and PE path");
        System.out.println("E: " + e_path_cnt);
        System.out.println("PE: " + pe_path_cnt);
        System.out.println("total: " + (e_path_cnt + pe_path_cnt));
    }

    private void printExploitableCount(int e_cnt, int pe_cnt, int ne_cnt) {
        System.out.println("Exploitable Analysis");
        System.out.println("E," + e_cnt);
        System.out.println("PE, " + pe_cnt);
        System.out.println("NE, " + ne_cnt);
        System.out.println("total: " + (e_cnt + pe_cnt + ne_cnt));
    }

    private Map<Long, CrashPoint> findFunctionFromCrashPointAddr() {
        Map<Long, CrashPoint> crashPointToFuncAddr = new HashMap<Long, CrashPoint>();

        if (singleCrashCheck) {
            crashPointToFuncAddr.putAll(CrashFileScanner.parseCrashFiles(null, module, crashAddr, singleCrashCheck));
            return crashPointToFuncAddr;
        }

        File[] subDirs;
        if (crashFolder != null && crashFolder.isDirectory()) {
            subDirs = crashFolder.listFiles();
            LogConsole.log("Folder count: " + Integer.toString(subDirs.length) + "\n");

            crashPointToFuncAddr.putAll(CrashFileScanner.parseCrashFiles(subDirs, module, crashAddr, singleCrashCheck));
            LogConsole.log("path   : \n");
            LogConsole.log("filter : \n");

        }
        return crashPointToFuncAddr;
    }

    private boolean hasFunctionCalls(ILatticeGraph<InstructionGraphNode> graph, Function curFunc) {

        List<FunctionEdge> edges = module.getCallgraph().getEdges();

        for (FunctionEdge edge : edges) {
            if (edge.getSource().getFunction().equals(curFunc)) {
                return true;
            }
        }

        return false;
    }

    private IStateVector<InstructionGraphNode, MLocLatticeElement> memoryAnalysis(
            ILatticeGraph<InstructionGraphNode> graph, Function curFunc,
            IStateVector<InstructionGraphNode, MLocLatticeElement> mLocResult) throws MLocException {
        // TODO Auto-generated method stub
        LogConsole.log("== start locAnalysis analysis ==\n");

        LogConsole.log("== find Heap Location ==\n");
        HeapChecker.initHeapChecker(graph, curFunc);

        CallStackCleaner callStackCleaner = CallStackCleaner.getCallStackCleaner();
        callStackCleaner.initCallStackCleaner(curFunc, graph);

        MLocAnalysis mLocAnalysis = new MLocAnalysis(graph, curFunc);

        LogConsole.log("== analysis start ==\n");
        mLocResult = mLocAnalysis.mLocAnalysis();
        mLocAnalysis.deleteTempReg(mLocResult);
        mLocAnalysis.deleteBottomSymbol(mLocResult);
        LogConsole.log("== end memoryAnalysis ==\n");
        // envAnalysis.printEnv(envResult);
        // LogConsole.log("== end print env analysis ===\n");

        return mLocResult;

    }

}
