package ffx.algorithms.groovy

import edu.rit.pj.ParallelTeam
import ffx.algorithms.cli.AlgorithmsScript
import ffx.crystal.CrystalPotential
import ffx.potential.MolecularAssembly
import ffx.potential.cli.AlchemicalOptions
import ffx.potential.cli.TopologyOptions
import ffx.potential.parsers.SystemFilter
import ffx.potential.parsers.XYZFilter
import org.apache.commons.configuration2.Configuration
import org.apache.commons.io.FilenameUtils
import picocli.CommandLine

import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

import static java.lang.String.format

/**
 * The SortArc script sort Monte Carlo archive files by lambda value. It presently assumes
 * that the number of files composing the first end of the window equals the number of files
 * composing the other end.
 * <br>
 * Usage:
 * <br>
 * ffxc SortArc [options] &lt;structures1&gt &lt;structures2&gt;
 */

@Command(description = " Unwind .ARC files for nWindows", name = "ffxc SortArc")
class SortArc extends AlgorithmsScript {

    @Mixin
    private AlchemicalOptions alchemical

    @Mixin
    private TopologyOptions topology

    @Option(names = ["--nw", "--nWindows"], paramLabel = "-1",
            description = "If set, auto-determine lambda values and subdirectories (overrides other flags).")
    private int nWindows = -1

    /**
     * The final argument(s) should be filenames for lambda windows in order..
     */
    @Parameters(arity = "1..*", paramLabel = "files",
            description = 'Trajectory files for the first end of the window, followed by trajectories for the other end')
    List<String> filenames = null

    private double[] lambdaValues
    private SystemFilter[] openers
    private SystemFilter[][] writers
    private String[] files
    private Configuration additionalProperties
    private List<String> windowFiles = new ArrayList<>()
    MolecularAssembly[] topologies
    private int threadsAvail = ParallelTeam.getDefaultThreadCount()
    private int threadsPer = threadsAvail


    /**
     * Sets an optional Configuration with additional properties.
     * @param additionalProps
     */
    void setProperties(Configuration additionalProps) {
        this.additionalProperties = additionalProps
    }

    /**
     * SortArc Constructor.
     */
    SortArc() {
        this(new Binding())
    }

    /**
     * SortArc Constructor.
     * @param binding The Groovy Binding to use.
     */
    SortArc(Binding binding) {
        super(binding)
    }

    @Override
    SortArc run() {
        if (!init()) {
            return this
        }

        int nTopology = filenames.size()
        files = new String[nTopology]
        for (int i = 0; i < nTopology; i++) {
            files[i] = filenames.get(i)
            logger.info(files[i])
        }

        if (nWindows != -1) {
            for (int i = 0; i < nWindows; i++) {
                for (int j = 0; j < nTopology; j++) {
                    String fullPathToFile = FilenameUtils.getFullPath(files[j])
                    String directoryFullPath = fullPathToFile.replace(files[j], "") + i;
                    windowFiles.add(directoryFullPath + File.separator + i)
                }

            }
            lambdaValues = new double[nWindows]
            for (int i = 0; i < nWindows; i++) {
                lambdaValues[i] = alchemical.getInitialLambda(nWindows, i, false);
            }
        }

        if (filenames == null) {
            return this
        }

        String[][] archiveFullPaths = new String[nWindows][nTopology]
        File file = new File(files[0])
        String directoryPath = file.getAbsoluteFile().getParent() + File.separator
        String[][] archiveNewPath = new String[nWindows][nTopology]
        File[][] saveFile = new File[nWindows][nTopology]
        File[][] arcFiles = new File[nWindows][nTopology]


        for (int j = 0; j < nTopology; j++) {
            logger.info("Directory path " + directoryPath)
            logger.info("the full path is" + FilenameUtils.getFullPath(files[j]))
            String archiveName = FilenameUtils.getBaseName(files[j]) + ".arc"

            for (int i = 0; i < nWindows; i++) {
                archiveFullPaths[i][j] = directoryPath + i + File.separator + archiveName

                File arcFile = new File(archiveFullPaths[i][j])
                arcFiles[i][j] = arcFile
                boolean fileExist = arcFile.exists()
                logger.info(archiveFullPaths[i][j] + ": " + fileExist.toString())
                archiveNewPath[i][j] = directoryPath + i + File.separator + FilenameUtils.getBaseName(files[j]) + "_E" + i + ".arc"
                logger.info(archiveNewPath[i][j])
                saveFile[i][j] = new File(archiveNewPath[i][j])
            }
        }

        openers = new XYZFilter[nTopology]
        writers = new XYZFilter[nWindows][nTopology]
        int numParallel = topology.getNumParallel(threadsAvail, nTopology)
        threadsPer = (int) (threadsAvail / numParallel)


        // Turn on computation of lambda derivatives if softcore atoms exist or a single topology.
        /* Checking nArgs == 1 should only be done for scripts that imply some sort of lambda scaling.
    The Minimize script, for example, may be running on a single, unscaled physical topology. */
        boolean lambdaTerm = (nTopology == 1 || alchemical.hasSoftcore() || topology.hasSoftcore())

        if (lambdaTerm) {
            System.setProperty("lambdaterm", "true")
        }

        // Relative free energies via the DualTopologyEnergy class require different
        // default OST parameters than absolute free energies.
        if (nTopology >= 2) {
            // Ligand vapor electrostatics are not calculated. This cancels when the
            // difference between protein and water environments is considered.
            System.setProperty("ligand-vapor-elec", "false")
        }

        topologies = new MolecularAssembly[nTopology]
        for (int j = 0; j < nTopology; j++) {
            MolecularAssembly ma =
                    alchemical.openFile(algorithmFunctions, topology, threadsPer, filenames[j], j)
            topologies[j] = ma
            openers[j] = algorithmFunctions.getFilter()

            for (int i = 0; i < nWindows; i++) {
                File arc = saveFile[i][j]
                logger.info(saveFile[i][j].toString())
                writers[i][j] = new XYZFilter(arc, topologies[j], topologies[j].getForceField(), additionalProperties)
            }
        }

        double tolerance = 1.0e-4

        for (int j = 0; j < nTopology; j++) {

            for (int i = 0; i < nWindows; i++) {

                logger.info(format(" Initializing %d topologies for each end", nTopology))
                openers[j].setFile(arcFiles[i][j])
                topologies[j].setFile(arcFiles[i][j])
                logger.info("Set file to:" + arcFiles[i][j].toString())


                int snapshots = openers[j].countNumModels()
                logger.info(snapshots.toString())

                for (int n = 0; n < snapshots; n++) {
                    boolean resetPosition = (n == 0) ? true : false;
                    openers[j].readNext(resetPosition, false)
                    String remarkLine = openers[j].getRemarkLines()



                    double lambda = 0
                    if (remarkLine.contains(" Lambda: ")) {
                        String[] tokens = remarkLine.split(" +")
                        for (int p = 0; p < tokens.length; p++) {
                            if (tokens[p].startsWith("Lambda")) {
                                lambda = Double.parseDouble(tokens[p + 1])
                            }
                        }

                    }

                    for(int k=0; k<nWindows; k++){
                        double diff = Math.abs(lambdaValues[k] - lambda)
                        if (diff < tolerance ) {
                            writers[k][j].writeFile(saveFile[k][j], true, remarkLine)
                            //set topology back to archive being read in
                            topologies[j].setFile(arcFiles[i][j])
                            break
                        }

                    }

                }

            }


        }


    }


}


