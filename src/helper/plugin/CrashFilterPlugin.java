package helper.plugin;


//Copyright 2011 Google Inc. All Rights Reserved.
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JMenuItem;

import com.google.common.base.Preconditions;
import com.google.security.zynamics.binnavi.API.disassembly.CouldntLoadDataException;
import com.google.security.zynamics.binnavi.API.disassembly.CouldntSaveDataException;
import com.google.security.zynamics.binnavi.API.disassembly.Function;
import com.google.security.zynamics.binnavi.API.disassembly.Instruction;
import com.google.security.zynamics.binnavi.API.disassembly.Module;
import com.google.security.zynamics.binnavi.API.disassembly.ModuleHelpers;
import com.google.security.zynamics.binnavi.API.disassembly.ModuleListenerAdapter;
import com.google.security.zynamics.binnavi.API.disassembly.PartialLoadException;
import com.google.security.zynamics.binnavi.API.disassembly.View;
import com.google.security.zynamics.binnavi.API.gui.LogConsole;
import com.google.security.zynamics.binnavi.API.helpers.MessageBox;
import com.google.security.zynamics.binnavi.API.plugins.IModuleMenuPlugin;
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

public final class CrashFilterPlugin implements IModuleMenuPlugin {

	private PluginInterface m_pluginInterface;
	private BinDialog dlg;

	private void showDialog(final Module module) {

		Preconditions.checkArgument(module.isLoaded(),
				"Internal Error: Target module is not loaded");

		dlg = new BinDialog(m_pluginInterface.getMainWindow().getFrame(),
				module, m_pluginInterface);
		GuiHelper2.centerChildToParent(m_pluginInterface.getMainWindow()
				.getFrame(), dlg, true);
		dlg.setVisible(true);
		// for every time when a user has not selected a function but a basic
		// block this breaks.
		// As it does throw a null pointer exception.

	}

	public List<JComponent> extendModuleMenu(List<Module> modules) {
		final List<JComponent> menus = new ArrayList<JComponent>();

		if (modules.size() == 1) {
			final Module targetModule = modules.get(0);

			menus.add(new JMenuItem(new AnalysisStart(targetModule)));
		}
		return menus;
	}

	public String getDescription() {
		return "Crash Filter is a tool checking vulnerabilities and exploitable possibility";
	}

	public long getGuid() {
		return 45235244566670943L;
	}

	public String getName() {
		return "CrashFilter Plugin";
	}

	public void init(final PluginInterface pluginInterface) {
		m_pluginInterface = pluginInterface;
	}

	public void unload() {
		// Not used yet
	}

	private class TXTFileFilter implements FilenameFilter {

		public boolean accept(File dir, String name) {
			return name.endsWith(".txt");
		}
	}

	private static class ActionUpdater extends ModuleListenerAdapter {
		private final AbstractAction m_action;

		public ActionUpdater(AbstractAction m_action) {
			super();
			this.m_action = m_action;
		}

		@Override
		public void loadedModule(Module arg0) {
			m_action.setEnabled(true);
		}

	}

	private class AnalysisStart extends AbstractAction {

		private static final long serialVersionUID = 5071188313367826333L;
		private final ActionUpdater m_updater = new ActionUpdater(this);
		Module module;

		public AnalysisStart(final Module module) {
			super("CrashFilter");
			this.module = module;
			setEnabled(module.isLoaded());
			module.addListener(m_updater);
		}

		@Override
		public void actionPerformed(final ActionEvent e) {

			showDialog(module);
		}
	}
}
