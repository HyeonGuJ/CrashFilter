package helper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.security.zynamics.binnavi.API.disassembly.Address;
import com.google.security.zynamics.binnavi.API.disassembly.Function;
import com.google.security.zynamics.binnavi.API.disassembly.Instruction;
import com.google.security.zynamics.binnavi.API.gui.LogConsole;
import com.google.security.zynamics.binnavi.API.reil.mono.ILatticeGraph;
import com.google.security.zynamics.binnavi.API.reil.mono.InstructionGraphNode;

import crashfilter.va.Val;
import crashfilter.va.MLocAnalysis.IValue;
import crashfilter.va.MLocAnalysis.RTable.RTable;
import crashfilter.va.MLocAnalysis.env.Env;
import crashfilter.va.memlocations.StructuredMLoc;
import crashfilter.va.regs.ActualReg;
import data.ReilInstructionResolve;

public class CallStackCleaner {
	
	private static CallStackCleaner callStackCleaner;
	private boolean callStackFlag = false;
	private Function function;
	private ILatticeGraph<InstructionGraphNode> graph;
	private List<Instruction> toBeClearedInstList = new ArrayList<Instruction>();	
	
	public  CallStackCleaner initCallStackCleaner(Function func, ILatticeGraph<InstructionGraphNode> instgraph)
	{
		function = func;
		graph = instgraph;
		
		for(InstructionGraphNode inst : graph.getNodes())
		{
			Address funcAddr = inst.getInstruction().getAddress();
			long funcAddrLong = funcAddr.toLong();
			if(funcAddrLong  % 0x100 != 0)
			{
				continue;
			}
			funcAddrLong /= 0x100;
			Instruction nativeInst = ReilInstructionResolve.findNativeInstruction(function, funcAddrLong);
			
			if(callStackFlag)
			{
				toBeClearedInstList.add(nativeInst);
				callStackFlag = false;
			}

			if(nativeInst.getMnemonic().equals("call"))
			{
				callStackFlag = true;
			}
		}
		
		return callStackCleaner;
	}
	
	public static CallStackCleaner getCallStackCleaner()
	{
		if(callStackCleaner == null)
		{
			callStackCleaner = new CallStackCleaner();
		}
		return callStackCleaner;
	}
	
	public boolean isToBeClearedStack(InstructionGraphNode reilInst)
	{
		//System.out.println(reilInst.getInstruction().getAddress().toLong());
		if(reilInst.getInstruction().getAddress().toLong() % 0x100 != 0)
		{
			return false;
		}
		for(Instruction instruction : toBeClearedInstList)
		{			
			if(instruction.getAddress().toLong() == reilInst.getInstruction().getAddress().toLong()/0x100)
			{
				return true;
			}
		}
		
		return false;
	}
	public void clearCallStack(InstructionGraphNode inst, RTable rTable, Env env)	
	{
		Address instAddr = inst.getInstruction().getAddress();
		long instAddrLong = instAddr.toLong();
		instAddrLong /= 0x100;
		Instruction nativeInst = ReilInstructionResolve.findNativeInstruction(function, instAddrLong);
		
		
		if(callStackFlag)
		{
			clearCallStack_Ebp(rTable, env);
			callStackFlag = false;
		}

		if(nativeInst.getMnemonic().equals("call"))
		{
			callStackFlag = true;
		}
	}
	public void clearCallStack_Ebp(RTable rTable, Env env)
	{
		Set<IValue> values = rTable.get(new ActualReg("esp"));
		Set<IValue> newValues = new HashSet<IValue>();
		for(IValue value : values)
		{
			if(value instanceof StructuredMLoc)
			{
				StructuredMLoc structuredValue = (StructuredMLoc) value;
				if(env.containsKey(structuredValue))
				{
					env.remove(structuredValue);
					Val ori = structuredValue.getC2();
					Val add4 = new Val(ori.getValue()+4);
					
					StructuredMLoc newStructuredValue = structuredValue.copy();
					newStructuredValue.setC2(add4);
					newValues.add(newStructuredValue);
				}
			}
		}
		rTable.remove(new ActualReg("esp"));
		rTable.put(new ActualReg("esp"), newValues);
	}
	
}
