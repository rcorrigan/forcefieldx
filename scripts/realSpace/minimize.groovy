// REALSPACE MINIMIZE

// Apache Imports
import org.apache.commons.io.FilenameUtils;

// Groovy Imports
import groovy.util.CliBuilder;

// Force Field X Imports
import ffx.xray.RealSpaceData;
import ffx.xray.RealSpaceFile;
import ffx.xray.RefinementMinimize;
import ffx.xray.RefinementMinimize.RefinementMode;

// RMS gradient per atom convergence criteria
double eps = 1.0;

// maximum number of refinement cycles
int maxiter = 1000;

// suffix to append to output data
String suffix = "_rsrefine";


// Things below this line normally do not need to be changed.
// ===============================================================================================

def today = new Date();
logger.info(" " + today);
logger.info(" command line variables:");
logger.info(" " + args + "\n");

// Create the command line parser.
def cli = new CliBuilder(usage:' ffxc realspace.minimize [options] <pdbfilename> [datafilename]');
cli.h(longOpt:'help', 'Print this help message.');
cli.d(longOpt:'data', args:2, valueSeparator:',', argName:'data.map,1.0', 'specify input data filename (or simply provide the datafilename argument after the PDB file) and weight to apply to the data (wA)');
cli.p(longOpt:'polarization', args:1, argName:'default', 'polarization model: [none / direct / default / tight]');
cli.e(longOpt:'eps', args:1, argName:'1.0', 'RMS gradient convergence criteria');
cli.m(longOpt:'maxiter', args:1, argName:'1000', 'maximum number of allowed refinement iterations');
cli.s(longOpt:'suffix', args:1, argName:'_rsrefine', 'output suffix');
def options = cli.parse(args);
List<String> arguments = options.arguments();
if (options.h || arguments == null || arguments.size() < 1) {
    return cli.usage();
}

// Name of the file (PDB or XYZ).
String modelfilename = arguments.get(0);

// set up real space map data (can be multiple files)
List mapfiles = new ArrayList();
if (arguments.size() > 1) {
    RealSpaceFile realspacefile = new RealSpaceFile(arguments.get(1), 1.0);
    mapfiles.add(realspacefile);
}
if (options.d) {
    for (int i=0; i<options.ds.size(); i+=2) {
	double wA = Double.parseDouble(options.ds[i+1]);
	RealSpaceFile realspacefile = new RealSpaceFile(options.ds[i], wA);
	mapfiles.add(realspacefile);
    }
}

if (options.e) {
    eps = Double.parseDouble(options.e);
}

if (options.m) {
    maxiter = Integer.parseInt(options.m);
}

if (options.s) {
    suffix = options.s;
}

if (options.p) {
    System.setProperty("polarization", options.p);
}

logger.info("\n Running x-ray minimize on " + modelfilename);
systems = open(modelfilename);

if (mapfiles.size() == 0) {
    RealSpaceFile realspacefile = new RealSpaceFile(systems, 1.0);
    mapfiles.add(realspacefile);
}

RealSpaceData realspacedata = new RealSpaceData(systems, systems[0].getProperties(), mapfiles.toArray(new RealSpaceFile[mapfiles.size()]));

energy();

RefinementMinimize refinementMinimize = new RefinementMinimize(diffractiondata, RefinementMode.COORDINATES);
if (eps < 0.0) {
    eps = 1.0;
}
logger.info("\n RMS gradient convergence criteria: " + eps + " max number of iterations: " + maxiter);
refinementMinimize.minimize(eps, maxiter);

energy();

saveAsPDB(systems, new File(FilenameUtils.removeExtension(modelfilename) + suffix + ".pdb"));
