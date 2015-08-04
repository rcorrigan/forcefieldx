
/**
 * Title: Force Field X.
 *
 * Description: Force Field X - Software for Molecular Biophysics.
 *
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2015.
 *
 * This file is part of Force Field X.
 *
 * Force Field X is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 as published by
 * the Free Software Foundation.
 *
 * Force Field X is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place, Suite 330, Boston, MA 02111-1307 USA
 *
 * Linking this library statically or dynamically with other modules is making a
 * combined work based on this library. Thus, the terms and conditions of the
 * GNU General Public License cover the whole combination.
 *
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent modules, and
 * to copy and distribute the resulting executable under terms of your choice,
 * provided that you also meet, for each linked independent module, the terms
 * and conditions of the license of that module. An independent module is a
 * module which is not derived from or based on this library. If you modify this
 * library, you may extend this exception to your version of the library, but
 * you are not obligated to do so. If you do not wish to do so, delete this
 * exception statement from your version.
 */

// Apache Imports
import org.apache.commons.io.FilenameUtils;
import org.biojava.nbio.structure.Structure;
import org.biojava.nbio.structure.align.StructureAlignment;
import org.biojava.nbio.structure.align.ce.CeMain;
import org.biojava.nbio.structure.align.model.AFPChain;
import org.biojava.nbio.structure.StructureTools;
import org.biojava.nbio.structure.StructureIO;
import org.biojava.nbio.structure.align.StructureAlignmentFactory;
import org.biojava.nbio.structure.align.ce.CeCPMain;
import org.biojava.nbio.structure.align.model.AFPChain;
import org.biojava.nbio.structure.align.StructureAlignment;
import org.biojava.nbio.structure.Atom;
import org.biojava.nbio.structure.align.ce.GuiWrapper;
import ffx.potential.MolecularAssembly;

// Groovy Imports
import ffx.algorithms.AlgorithmFunctions
import ffx.algorithms.AlgorithmUtils
import groovy.util.CliBuilder;

// Force Field X Imports


// Things below this line normally do not need to be changed.
// ===============================================================================================
//boolean xray = false;
//int cycles = 2; // Only meaningful for X-ray refinement.
double eps = 1.0;
//double beps = -1.0;
//int maxiter = 1000;
boolean display = false;
boolean allAtom = false;

// Create the command line parser.
def cli = new CliBuilder(usage:' ffxc xray.minAndAlign <pdb code 1> <pdb code 2>');
cli.h(longOpt:'help', 'Print this help message.');
//cli.x(longOpt:'xray', 'Minimize using X-ray target');
//cli.c(longOpt:'cycles', args:1, argName:'2', 'Cycles of refinement to get to final convergence criteria (only meaningful for X-ray).');
cli.e(longOpt:'eps', args:1, argName:'1.0', 'RMS gradient convergence criteria for coordinates.');
//cli.be(longOpt:'bfac-eps', args:1, argName:'-1.0', 'RMS gradient convergence criteria for B-factors (negative: automatically determine).')
//cli.i(longOpt:'maxiter', args:1, argName:'1000', 'maximum number of allowed refinement iterations.');
cli.a(longOpt:'allAtom', args:1, argName:'false', 'Use all atoms (true) or alpha carbons only (false) for alignment.');
cli.d(longOpt:'display', args:1, argName:'false', 'Display final alignment in Jmol session.');

def options = cli.parse(args);
List<String> arguments = options.arguments();
if (options.h) {
    return cli.usage();
}

if (options.e) {
    eps = Double.parseDouble(options.e);
}

if (options.d) {
    display = Boolean.parseBoolean(options.d);
}

if (options.a) {
    allAtom = Boolean.parseBoolean(options.a);
}

String pdbcode1;
String pdbcode2;
if (arguments != null && arguments.size() == 2) {
    pdbcode1 = arguments.get(0);
    pdbcode2 = arguments.get(1);
} else {
    return cli.usage();
}

Structure struct1 = StructureIO.getStructure(pdbcode1);
Structure struct2 = StructureIO.getStructure(pdbcode2);
org.biojava.nbio.structure.Atom[] atoms1;
org.biojava.nbio.structure.Atom[] atoms2;
if (allAtom) {
    atoms1 = StructureTools.getAllAtomArray(struct1);
    atoms2 = StructureTools.getAllAtomArray(struct2);
} else {
    atoms1 = StructureTools.getAtomCAArray(struct1);
    atoms2 = StructureTools.getAtomCAArray(struct2);
}

StructureAlignment algorithm = StructureAlignmentFactory.getAlgorithm(CeMain.algorithmName);
AFPChain afpChain;
logger.info(" Aligning original structures.");
afpChain = algorithm.align(atoms1, atoms2);
logger.info(afpChain.toCE(atoms1, atoms2));


AlgorithmFunctions functions;
try {
    functions = getPotentialsFunctions();
} catch (MissingMethodException ex) {
    functions = new AlgorithmUtils();
}

MolecularAssembly[] mas1 = functions.convertDataStructure(struct1);
MolecularAssembly assem1 = mas1[0];

functions.minimize(assem1, eps);
logger.info(" Aligning structure 1 with minimized structure 1.");
org.biojava.nbio.structure.Atom[] newAtoms1;
if (allAtom) {
    newAtoms1 = StructureTools.getAllAtomArray(assem1);
} else {
    newAtoms1 = StructureTools.getAtomCAArray(assem1);
}

afpChain = algorithm.align(newAtoms1, atoms1);
logger.info(afpChain.toCE(newAtoms1, atoms1));

MolecularAssembly[] mas2 = functions.convertDataStructure(struct2);
MolecularAssembly assem2 = mas2[0];

functions.minimize(assem2, eps);
logger.info(" Aligning structure 2 with minimized structure 2.");
org.biojava.nbio.structure.Atom[] newAtoms2;
if (allAtom) {
    newAtoms2 = StructureTools.getAllAtomArray(assem2);
} else {
    newAtoms2 = StructureTools.getAtomCAArray(assem2);
}

afpChain = algorithm.align(newAtoms2, atoms2);
logger.info(afpChain.toCE(newAtoms2, atoms2));

logger.info(" Aligning minimized structures.");
afpChain = algorithm.align(newAtoms1, newAtoms2);
logger.info(afpChain.toCE(newAtoms1, newAtoms2));

if (display) {
    String headlessString = System.getProperty("java.awt.headless");
    boolean isHeadless = Boolean.parseBoolean(headlessString);
    
    if (!isHeadless) {
        GuiWrapper.display(afpChain, newAtoms1, newAtoms2);
    } else {
        logger.info(" Cannot display alignment; currently running a headless JVM (run using ffx, not ffxc to enable GUI)");
    }
}
