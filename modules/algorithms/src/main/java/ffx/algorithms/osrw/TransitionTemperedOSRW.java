/**
 * Title: Force Field X.
 * <p>
 * Description: Force Field X - Software for Molecular Biophysics.
 * <p>
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2019.
 * <p>
 * This file is part of Force Field X.
 * <p>
 * Force Field X is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 as published by
 * the Free Software Foundation.
 * <p>
 * Force Field X is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place, Suite 330, Boston, MA 02111-1307 USA
 * <p>
 * Linking this library statically or dynamically with other modules is making a
 * combined work based on this library. Thus, the terms and conditions of the
 * GNU General Public License cover the whole combination.
 * <p>
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
package ffx.algorithms.osrw;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import static java.lang.String.format;
import static java.util.Arrays.fill;

import org.apache.commons.configuration2.CompositeConfiguration;
import static org.apache.commons.math3.util.FastMath.abs;
import static org.apache.commons.math3.util.FastMath.exp;
import static org.apache.commons.math3.util.FastMath.max;
import static org.apache.commons.math3.util.FastMath.min;
import static org.apache.commons.math3.util.FastMath.pow;
import static org.apache.commons.math3.util.FastMath.sqrt;

import edu.rit.mp.DoubleBuf;

import ffx.algorithms.AlgorithmListener;
import ffx.algorithms.Minimize;
import ffx.algorithms.thermostats.Thermostat;
import ffx.crystal.Crystal;
import ffx.crystal.CrystalPotential;
import ffx.numerics.Potential;
import ffx.numerics.integrate.DataSet;
import ffx.numerics.integrate.DoublesDataSet;
import ffx.numerics.integrate.Integrate1DNumeric;
import ffx.numerics.integrate.Integrate1DNumeric.IntegrationType;
import ffx.potential.bonded.LambdaInterface;
import ffx.potential.utils.EnergyException;
import static ffx.numerics.integrate.Integrate1DNumeric.IntegrationType.SIMPSONS;

/**
 * An implementation of Transition-Tempered Orthogonal Space Random Walk
 * algorithm.
 * <p>
 * Only partially implements LambdaInterface; does not return 2'nd lambda
 * derivatives, as 2'nd derivatives of the bias require 3'rd derivatives of
 * the underlying Hamiltonian.
 *
 * @author Michael J. Schnieders, James Dama, Wei Yang and Pengyu Ren
 */
public class TransitionTemperedOSRW extends AbstractOSRW implements LambdaInterface {

    private static final Logger logger = Logger.getLogger(TransitionTemperedOSRW.class.getName());

    /**
     * The recursion kernel stores the weight of each [lambda][Flambda] bin.
     */
    private double recursionKernel[][];
    /**
     * The recursionWeights stores the [Lambda, FLambda] weight for each
     * process. Therefore the array is of size [number of Processes][2].
     * <p>
     * Each 2 entry array must be wrapped inside a Parallel Java IntegerBuf for the
     * All-Gather communication calls.
     */
    private final double recursionWeights[][];
    private final double myRecursionWeight[];
    /**
     * These DoubleBufs wrap the recursionWeight arrays.
     */
    private final DoubleBuf recursionWeightsBuf[];
    private final DoubleBuf myRecursionWeightBuf;

    /**
     * A flag to indicate if the transition has been crossed and Dama et al.
     * transition-tempering should begin.
     * <p>
     * Currently "tempering" is always true, but a temperOffset (default 1.0 kcal/mol) prevents tempering
     * prior to there being a bias everywhere along the lambda path of at least temperOffset.
     */
    private boolean tempering = true;
    /**
     * The Dama et al. transition-tempering rate parameter. A reasonable value
     * is about 2 to 8 kT, with larger values being more conservative (slower decay).
     * <p>
     * The default temperingFactor = 8.0.
     */
    private double temperingFactor = 8.0;
    /**
     * This deltaT is used to determine the tempering weight as described below for the temperingWeight variable.
     * <p>
     * deltaT = temperingFactor * kB * T.
     */
    private double deltaT;
    /**
     * The Dama et al. transition-tempering weight.
     * <p>
     * The initial temperingWeight = 1.0, and more generally temperingWeight = exp(-biasHeight/deltaT)
     */
    private double temperingWeight = 1.0;
    /**
     * An offset applied before calculating tempering weight.
     * <p>
     * First, for every Lambda, we calculate the maximum bias at that lambda by searching all populated dU/dL bins:
     * maxdUdL(L) = max[ G(L,F_L) ] where the max operator searches over all F_L bins.
     * <p>
     * Then, the minimum bias coverage is determined by searching the maxdUdL(L) array over Lambda.
     * minBias = min[ maxdUdL(L) ] where the min operator searches all entries in the array.
     * <p>
     * Then the temperOffset is applied to the minBias:
     * <p>
     * biasHeight = max[minBias - temperOffset, 0]
     * <p>
     * The default temperOffset = 1.0.
     */
    private double temperOffset;
    /**
     * If the recursion kernel becomes too large for some combinations of (Lambda, dU/dL),
     * then its statistical weight = exp(kernel * beta) will exceed the maximum representable double value.
     *
     * In this case, we can simply apply a lambda bin dependent negative offset to all kernel values.
     */
    private double[] kernelOffset;

    /**
     * The ReceiveThread accumulates OSRW statistics from multiple asynchronous walkers.
     */
    private final ReceiveThread receiveThread;

    private final IntegrationType integrationType;

    /**
     * OSRW Asynchronous MultiWalker Constructor.
     *
     * @param lambdaInterface   defines Lambda and dU/dL.
     * @param potential         defines the Potential energy.
     * @param lambdaFile        contains the current Lambda particle position and
     *                          velocity.
     * @param histogramFile     contains the Lambda and dU/dL histogram.
     * @param properties        defines System properties.
     * @param temperature       the simulation temperature.
     * @param dt                the time step.
     * @param printInterval     number of steps between logging updates.
     * @param saveInterval      number of steps between restart file updates.
     * @param asynchronous      set to true if walkers run asynchronously.
     * @param algorithmListener the AlgorithmListener to be notified of
     *                          progress.
     */
    public TransitionTemperedOSRW(LambdaInterface lambdaInterface, CrystalPotential potential,
                                  File lambdaFile, File histogramFile, CompositeConfiguration properties,
                                  double temperature, double dt, double printInterval,
                                  double saveInterval, boolean asynchronous,
                                  AlgorithmListener algorithmListener) {
        this(lambdaInterface, potential, lambdaFile, histogramFile, properties,
                temperature, dt, printInterval, saveInterval, asynchronous,
                true, algorithmListener);
    }

    /**
     * OSRW Asynchronous MultiWalker Constructor.
     *
     * @param lambdaInterface   defines Lambda and dU/dL.
     * @param potential         defines the Potential energy.
     * @param lambdaFile        contains the current Lambda particle position and
     *                          velocity.
     * @param histogramFile     contains the Lambda and dU/dL histogram.
     * @param properties        defines System properties.
     * @param temperature       the simulation temperature.
     * @param dt                the time step.
     * @param printInterval     number of steps between logging updates.
     * @param saveInterval      number of steps between restart file updates.
     * @param asynchronous      set to true if walkers run asynchronously.
     * @param resetNumSteps     whether to reset energy counts to 0
     * @param algorithmListener the AlgorithmListener to be notified of
     *                          progress.
     */
    public TransitionTemperedOSRW(LambdaInterface lambdaInterface, CrystalPotential potential,
                                  File lambdaFile, File histogramFile, CompositeConfiguration properties,
                                  double temperature, double dt, double printInterval,
                                  double saveInterval, boolean asynchronous, boolean resetNumSteps,
                                  AlgorithmListener algorithmListener) {
        super(lambdaInterface, potential, lambdaFile, histogramFile, properties,
                temperature, dt, printInterval, saveInterval, asynchronous, resetNumSteps, algorithmListener);

        deltaT = temperingFactor * Thermostat.R * this.temperature;

        /**
         * Allocate space for the recursion kernel that stores weights.
         */
        recursionKernel = new double[lambdaBins][FLambdaBins];

        kernelOffset = new double[lambdaBins];

        /**
         * Load the OSRW histogram restart file if it exists.
         */
        boolean readHistogramRestart = false;
        if (histogramFile != null && histogramFile.exists()) {
            try {
                TTOSRWHistogramReader osrwHistogramReader = new TTOSRWHistogramReader(new FileReader(histogramFile));
                osrwHistogramReader.readHistogramFile();
                logger.info(String.format("\n Continuing OSRW histogram from %s.", histogramFile.getName()));
                readHistogramRestart = true;
            } catch (FileNotFoundException ex) {
                logger.info(" Histogram restart file could not be found and will be ignored.");
            }
        }

        /**
         * Load the OSRW lambda restart file if it exists.
         */
        if (lambdaFile != null && lambdaFile.exists()) {
            try {
                TTOSRWLambdaReader osrwLambdaReader = new TTOSRWLambdaReader(new FileReader(lambdaFile));
                osrwLambdaReader.readLambdaFile(resetNumSteps);
                logger.info(String.format("\n Continuing OSRW lambda from %s.", lambdaFile.getName()));
            } catch (FileNotFoundException ex) {
                logger.info(" Lambda restart file could not be found and will be ignored.");
            }
        }

        if (asynchronous) {
            /**
             * Use asynchronous communication.
             */
            myRecursionWeight = new double[3];
            myRecursionWeightBuf = DoubleBuf.buffer(myRecursionWeight);

            receiveThread = new ReceiveThread();
            receiveThread.start();
            recursionWeights = null;
            recursionWeightsBuf = null;
        } else {
            /**
             * Use synchronous communication.
             */
            recursionWeights = new double[numProc][3];
            recursionWeightsBuf = new DoubleBuf[numProc];
            for (int i = 0; i < numProc; i++) {
                recursionWeightsBuf[i] = DoubleBuf.buffer(recursionWeights[i]);
            }
            myRecursionWeight = recursionWeights[rank];
            myRecursionWeightBuf = recursionWeightsBuf[rank];
            receiveThread = null;
        }

        String propString = System.getProperty("ttosrw-temperOffset", "1");
        temperOffset = 1;
        try {
            temperOffset = Double.parseDouble(propString);
        } catch (NumberFormatException ex) {
            logger.info(String.format(" Exception in parsing ttosrw-temperOffset, resetting to 1.0 kcal/mol: %s", ex.toString()));
            temperOffset = 1;
        }

         if (temperOffset < 0) {
            temperOffset = 0;
        }
        logger.info(format("  Coverage before tempering:     %7.4g kcal/mol", temperOffset));

        propString = System.getProperty("ttosrw-integrationType", "SIMPSONS");
        IntegrationType testType = SIMPSONS;
        try {
            testType = IntegrationType.valueOf(propString.toUpperCase());
        } catch (Exception ex) {
            logger.warning(String.format(" Invalid argument %s to ttosrw-integrationType; resetting to SIMPSONS", propString));
            testType = SIMPSONS;
        }
        integrationType = testType;

        /**
         * Update and print out the recursion slave.
         */
        if (readHistogramRestart) {
            updateFLambda(true);
        }
    }

    /**
     * {@inheritDoc}
     */
    public double energy(double[] x) {

        forceFieldEnergy = potential.energy(x);

        /**
         * OSRW is propagated with the slowly varying terms.
         */
        if (state == STATE.FAST) {
            return forceFieldEnergy;
        }

        gLdEdL = 0.0;
        dUdLambda = lambdaInterface.getdEdL();
        d2UdL2 = lambdaInterface.getd2EdL2();
        int lambdaBin = binForLambda(lambda);
        int FLambdaBin = binForFLambda(dUdLambda);
        dForceFieldEnergydL = dUdLambda;

        /**
         * Calculate recursion kernel G(L, F_L) and its derivatives with respect
         * to L and F_L.
         */
        double dGdLambda = 0.0;
        double dGdFLambda = 0.0;
        double ls2 = (2.0 * dL) * (2.0 * dL);
        double FLs2 = (2.0 * dFL) * (2.0 * dFL);
        for (int iL = -biasCutoff; iL <= biasCutoff; iL++) {
            int lcenter = lambdaBin + iL;
            double deltaL = lambda - (lcenter * dL);
            double deltaL2 = deltaL * deltaL;
            // Mirror conditions for recursion kernel counts.
            int lcount = lcenter;
            double mirrorFactor = 1.0;
            if (lcount == 0 || lcount == lambdaBins - 1) {
                mirrorFactor = 2.0;
            } else if (lcount < 0) {
                lcount = -lcount;
            } else if (lcount > lambdaBins - 1) {
                // Number of bins past the last bin
                lcount -= (lambdaBins - 1);
                // Mirror bin
                lcount = lambdaBins - 1 - lcount;
            }
            for (int iFL = -biasCutoff; iFL <= biasCutoff; iFL++) {
                int FLcenter = FLambdaBin + iFL;
                /**
                 * If either of the following FL edge conditions are true, then
                 * there are no counts and we continue.
                 */
                if (FLcenter < 0 || FLcenter >= FLambdaBins) {
                    continue;
                }
                double deltaFL = dUdLambda - (minFLambda + FLcenter * dFL + dFL_2);
                double deltaFL2 = deltaFL * deltaFL;
                double weight = mirrorFactor * recursionKernel[lcount][FLcenter];
                double bias = weight * biasMag
                        * exp(-deltaL2 / (2.0 * ls2))
                        * exp(-deltaFL2 / (2.0 * FLs2));
                gLdEdL += bias;
                dGdLambda -= deltaL / ls2 * bias;
                dGdFLambda -= deltaFL / FLs2 * bias;
            }
        }

        /**
         * Lambda gradient due to recursion kernel G(L, F_L).
         */
        dUdLambda += dGdLambda + dGdFLambda * d2UdL2;

        /**
         * Compute the energy and gradient for the recursion slave at F(L) using
         * interpolation.
         */
        biasEnergy = current1DBiasEnergy(lambda, true) + gLdEdL;

        if (print) {
            logger.info(String.format(" %s %16.8f", "Bias Energy       ", biasEnergy));
            logger.info(String.format(" %s %16.8f  %s",
                    "OSRW Potential    ", forceFieldEnergy + biasEnergy, "(Kcal/mole)"));
        }
        
        if(mcRestart){
            energyCount++;
        }

        if (propagateLambda) {
            energyCount++;

            /**
             * Log the current Lambda state.
             */
            if (energyCount % printFrequency == 0) {
                double dBdL = dUdLambda - dForceFieldEnergydL;
                if (lambdaBins < 1000) {
                    logger.info(String.format(" L=%6.4f (%3d) F_LU=%10.4f F_LB=%10.4f F_L=%10.4f V_L=%10.4f",
                            lambda, lambdaBin, dForceFieldEnergydL, dBdL, dUdLambda, halfThetaVelocity));
                } else {
                    logger.info(String.format(" L=%6.4f (%4d) F_LU=%10.4f F_LB=%10.4f F_L=%10.4f V_L=%10.4f",
                            lambda, lambdaBin, dForceFieldEnergydL, dBdL, dUdLambda, halfThetaVelocity));
                }
            }

            /**
             * Metadynamics grid counts (every 'countInterval' steps).
             */
            if (energyCount % countInterval == 0) {
                addBias(dForceFieldEnergydL, x, null);
            }

            langevin();
        }

        totalEnergy = forceFieldEnergy + biasEnergy;

        return totalEnergy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double energyAndGradient(double[] x, double[] gradient) {

        forceFieldEnergy = potential.energyAndGradient(x, gradient);

        /**
         * OSRW is propagated with the slowly varying terms.
         */
        if (state == STATE.FAST) {
            return forceFieldEnergy;
        }

        gLdEdL = 0.0;
        dForceFieldEnergydL = lambdaInterface.getdEdL();
        dUdLambda = dForceFieldEnergydL;

        d2UdL2 = lambdaInterface.getd2EdL2();
        int lambdaBin = binForLambda(lambda);
        int FLambdaBin = binForFLambda(dUdLambda);

        /**
         * Calculate recursion kernel G(L, F_L) and its derivatives with respect
         * to L and F_L.
         */
        dGdLambda = 0.0;
        dGdFLambda = 0.0;
        double ls2 = (2.0 * dL) * (2.0 * dL);
        double FLs2 = (2.0 * dFL) * (2.0 * dFL);
        for (int iL = -biasCutoff; iL <= biasCutoff; iL++) {
            int lcenter = lambdaBin + iL;
            double deltaL = lambda - (lcenter * dL);
            double deltaL2 = deltaL * deltaL;
            // Mirror conditions for recursion kernel counts.
            int lcount = lcenter;
            double mirrorFactor = 1.0;
            if (lcount == 0 || lcount == lambdaBins - 1) {
                mirrorFactor = 2.0;
            } else if (lcount < 0) {
                lcount = -lcount;
            } else if (lcount > lambdaBins - 1) {
                // Number of bins past the last bin
                lcount -= (lambdaBins - 1);
                // Mirror bin
                lcount = lambdaBins - 1 - lcount;
            }
            for (int iFL = -biasCutoff; iFL <= biasCutoff; iFL++) {
                int FLcenter = FLambdaBin + iFL;
                /**
                 * If either of the following FL edge conditions are true, then
                 * there are no counts and we continue.
                 */
                if (FLcenter < 0 || FLcenter >= FLambdaBins) {
                    continue;
                }
                double deltaFL = dUdLambda - (minFLambda + FLcenter * dFL + dFL_2);
                double deltaFL2 = deltaFL * deltaFL;
                double weight = mirrorFactor * recursionKernel[lcount][FLcenter];
                double bias = weight * biasMag
                        * exp(-deltaL2 / (2.0 * ls2))
                        * exp(-deltaFL2 / (2.0 * FLs2));
                gLdEdL += bias;
                dGdLambda -= deltaL / ls2 * bias;
                dGdFLambda -= deltaFL / FLs2 * bias;
            }
        }

        /**
         * Lambda gradient due to recursion kernel G(L, F_L).
         */
        dUdLambda += dGdLambda + dGdFLambda * d2UdL2;

        /**
         * Cartesian coordinate gradient due to recursion kernel G(L, F_L).
         */
        fill(dUdXdL, 0.0);
        lambdaInterface.getdEdXdL(dUdXdL);
        for (int i = 0; i < nVariables; i++) {
            gradient[i] += dGdFLambda * dUdXdL[i];
        }

        /**
         * Compute the energy and gradient for the recursion slave at F(L) using
         * interpolation.
         */
        biasEnergy = current1DBiasEnergy(lambda, true) + gLdEdL;

        if (print) {
            logger.info(String.format(" %s %16.8f", "Bias Energy       ", biasEnergy));
            logger.info(String.format(" %s %16.8f  %s",
                    "OSRW Potential    ", forceFieldEnergy + biasEnergy, "(Kcal/mole)"));
        }
        
        if(mcRestart){
            energyCount++;
        }

        if (propagateLambda) {
            energyCount++;

            /**
             * Log the current Lambda state.
             */
            if (energyCount % printFrequency == 0) {
                double dBdL = dUdLambda - dForceFieldEnergydL;
                if (lambdaBins < 1000) {
                    logger.info(String.format(" L=%6.4f (%3d) F_LU=%10.4f F_LB=%10.4f F_L=%10.4f V_L=%10.4f",
                            lambda, lambdaBin, dForceFieldEnergydL, dBdL, dUdLambda, halfThetaVelocity));
                } else {
                    logger.info(String.format(" L=%6.4f (%4d) F_LU=%10.4f F_LB=%10.4f F_L=%10.4f V_L=%10.4f",
                            lambda, lambdaBin, dForceFieldEnergydL, dBdL, dUdLambda, halfThetaVelocity));
                }
            }

            /**
             * Metadynamics grid counts (every 'countInterval' steps).
             */
            if (energyCount % countInterval == 0) {
                addBias(dForceFieldEnergydL, x, gradient);
            }

            langevin();
        }

        totalEnergy = forceFieldEnergy + biasEnergy;

        return totalEnergy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addBias(double dEdU, double[] x, double[] gradient) {

        if (asynchronous) {
            asynchronousSend(lambda, dEdU);
        } else {
            synchronousSend(lambda, dEdU);
        }

        biasCount++;

        /**
         * Update F(L)
         */
        fLambdaUpdates++;
        boolean printFLambda = fLambdaUpdates % fLambdaPrintInterval == 0;
        totalFreeEnergy = updateFLambda(printFLambda);

        /**
         * Calculating Moving Average & Standard Deviation
         */
        totalAverage += totalFreeEnergy;
        totalSquare += pow(totalFreeEnergy, 2);
        periodCount++;
        if (periodCount == window - 1) {
            lastAverage = totalAverage / window;
            lastStdDev = sqrt((totalSquare - (totalAverage * totalAverage)) / (window * window));
            logger.info(format(" The running average is %12.4f kcal/mol and the stdev is %8.4f kcal/mol.", lastAverage, lastStdDev));
            totalAverage = 0;
            totalSquare = 0;
            periodCount = 0;
        }

        if (osrwOptimization && lambda > osrwOptimizationLambdaCutoff) {
            optimization(forceFieldEnergy, x, gradient);
        }
        
        if(mcRestart){
            return;
        }

        /**
         * Write out restart files.
         */
        if (energyCount > 0 && energyCount % saveFrequency == 0) {
            if (algorithmListener != null) {
                algorithmListener.algorithmUpdate(molecularAssembly);
            }
            /**
             * Only the rank 0 process writes the histogram restart file.
             */
            if (rank == 0) {
                try {
                    TTOSRWHistogramWriter ttOSRWHistogramRestart = new TTOSRWHistogramWriter(
                            new BufferedWriter(new FileWriter(histogramFile)));
                    ttOSRWHistogramRestart.writeHistogramFile();
                    ttOSRWHistogramRestart.flush();
                    ttOSRWHistogramRestart.close();
                    logger.info(String.format(" Wrote TTOSRW histogram restart file to %s.", histogramFile.getName()));
                } catch (IOException ex) {
                    String message = " Exception writing TTOSRW histogram restart file.";
                    logger.log(Level.INFO, message, ex);
                }
            }
            /**
             * All ranks write a lambda restart file.
             */
            try {
                TTOSRWLambdaWriter ttOSRWLambdaRestart = new TTOSRWLambdaWriter(new BufferedWriter(new FileWriter(lambdaFile)));
                ttOSRWLambdaRestart.writeLambdaFile();
                ttOSRWLambdaRestart.flush();
                ttOSRWLambdaRestart.close();
                logger.info(String.format(" Wrote TTOSRW lambda restart file to %s.", lambdaFile.getName()));
            } catch (IOException ex) {
                String message = " Exception writing TTOSRW lambda restart file.";
                logger.log(Level.INFO, message, ex);
            }
        }
    }
    
    @Override
    public void writeRestart(){
        if (algorithmListener != null) {
                algorithmListener.algorithmUpdate(molecularAssembly);
            }
            /**
             * Only the rank 0 process writes the histogram restart file.
             */
            if (rank == 0) {
                try {
                    TTOSRWHistogramWriter ttOSRWHistogramRestart = new TTOSRWHistogramWriter(
                            new BufferedWriter(new FileWriter(histogramFile)));
                    ttOSRWHistogramRestart.writeHistogramFile();
                    ttOSRWHistogramRestart.flush();
                    ttOSRWHistogramRestart.close();
                    logger.info(String.format(" Wrote TTOSRW histogram restart file to %s.", histogramFile.getName()));
                } catch (IOException ex) {
                    String message = " Exception writing TTOSRW histogram restart file.";
                    logger.log(Level.INFO, message, ex);
                }
            }
            /**
             * All ranks write a lambda restart file.
             */
            try {
                TTOSRWLambdaWriter ttOSRWLambdaRestart = new TTOSRWLambdaWriter(new BufferedWriter(new FileWriter(lambdaFile)));
                ttOSRWLambdaRestart.writeLambdaFile();
                ttOSRWLambdaRestart.flush();
                ttOSRWLambdaRestart.close();
                logger.info(String.format(" Wrote TTOSRW lambda restart file to %s.", lambdaFile.getName()));
            } catch (IOException ex) {
                String message = " Exception writing TTOSRW lambda restart file.";
                logger.log(Level.INFO, message, ex);
            }
    }

    public double computeBiasEnergy(double currentLambda, double currentdUdL) {

        int lambdaBin = binForLambda(currentLambda);
        int FLambdaBin = binForFLambda(currentdUdL);

        double gLdEdL = 0.0;

        double ls2 = (2.0 * dL) * (2.0 * dL);
        double FLs2 = (2.0 * dFL) * (2.0 * dFL);
        for (int iL = -biasCutoff; iL <= biasCutoff; iL++) {
            int lcenter = lambdaBin + iL;
            double deltaL = currentLambda - (lcenter * dL);
            double deltaL2 = deltaL * deltaL;
            // Mirror conditions for recursion kernel counts.
            int lcount = lcenter;
            double mirrorFactor = 1.0;
            if (lcount == 0 || lcount == lambdaBins - 1) {
                mirrorFactor = 2.0;
            } else if (lcount < 0) {
                lcount = -lcount;
            } else if (lcount > lambdaBins - 1) {
                // Number of bins past the last bin
                lcount -= (lambdaBins - 1);
                // Mirror bin
                lcount = lambdaBins - 1 - lcount;
            }
            for (int iFL = -biasCutoff; iFL <= biasCutoff; iFL++) {
                int FLcenter = FLambdaBin + iFL;
                /**
                 * If either of the following FL edge conditions are true, then
                 * there are no counts and we continue.
                 */
                if (FLcenter < 0 || FLcenter >= FLambdaBins) {
                    continue;
                }
                double deltaFL = currentdUdL - (minFLambda + FLcenter * dFL + dFL_2);
                double deltaFL2 = deltaFL * deltaFL;
                double weight = mirrorFactor * recursionKernel[lcount][FLcenter];
                double bias = weight * biasMag
                        * exp(-deltaL2 / (2.0 * ls2))
                        * exp(-deltaFL2 / (2.0 * FLs2));
                gLdEdL += bias;
            }
        }

        /**
         * Compute the energy and gradient for the recursion slave at F(L) using
         * interpolation.
         */
        return current1DBiasEnergy(currentLambda, false) + gLdEdL;
    }

    private void optimization(double e, double x[], double gradient[]) {
        if (energyCount % osrwOptimizationFrequency == 0) {
            logger.info(String.format(" OSRW Minimization (Step %d)", energyCount));

            // Set the underlying Potential's Lambda value to 1.0.
            lambdaInterface.setLambda(1.0);

            potential.setEnergyTermState(Potential.STATE.BOTH);

            if (barostat != null) {
                barostat.setActive(false);
            }

            try {
                // Optimize the system.
                double startingEnergy = potential.energy(x);

                Minimize minimize = new Minimize(null, potential, null);
                minimize.minimize(osrwOptimizationEps);

                // Collect the minimum energy.
                double minEnergy = potential.getTotalEnergy();
                // Check for a new minimum within an energy window of the lowest energy structure found.
                if (minEnergy < osrwOptimum + osrwOptimizationEnergyWindow) {
                    if (minEnergy < osrwOptimum) {
                        osrwOptimum = minEnergy;
                    }
                    int n = potential.getNumberOfVariables();
                    osrwOptimumCoords = new double[n];
                    osrwOptimumCoords = potential.getCoordinates(osrwOptimumCoords);
                    double mass = molecularAssembly.getMass();
                    Crystal crystal = molecularAssembly.getCrystal();
                    double density = crystal.getDensity(mass);
                    osrwOptimizationFilter.writeFile(osrwOptimizationFile, false);
                    Crystal uc = crystal.getUnitCell();
                    logger.info(String.format(" Minimum: %12.6f %s (%12.6f g/cc) optimized from %12.6f at step %d.",
                            minEnergy, uc.toShortString(), density, startingEnergy, energyCount));
                }
            } catch (EnergyException ex) {
                String message = ex.getMessage();
                logger.info(format(" Energy exception minimizing coordinates at lambda=%8.6f\n %s.", lambda, message));
                logger.info(format(" TT-OSRW sampling will continue."));
            }

            // Set the underlying Potential's Lambda value back to current lambda value.
            lambdaInterface.setLambda(lambda);

            // Reset the Potential State
            potential.setEnergyTermState(state);

            // Reset the Barostat
            if (barostat != null) {
                barostat.setActive(true);
            }

            // Revert to the coordinates and gradient prior to optimization.
            double eCheck = potential.energyAndGradient(x, gradient);

            if (abs(eCheck - e) > osrwOptimizationTolerance) {
                logger.warning(String.format(
                        " TT-OSRW optimization could not revert coordinates %16.8f vs. %16.8f.", e, eCheck));
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Evaluate the bias at [cLambda, cF_lambda]
     */
    @Override
    protected double evaluateKernel(int cLambda, int cF_Lambda) {
        /**
         * Compute the value of L and FL for the center of the current bin.
         */
        double vL = cLambda * dL;
        double vFL = minFLambda + cF_Lambda * dFL + dFL_2;

        /**
         * Set the variances for the Gaussian bias.
         */
        double Ls2 = 2.0 * dL * 2.0 * dL;
        double FLs2 = 2.0 * dFL * 2.0 * dFL;
        /**
         * Variances are only used when dividing by twice their value, so pre-compute!
         */
        double invLs2 = 0.5 / Ls2;
        double invFLs2 = 0.5 / FLs2;

        double sum = 0.0;
        for (int iL = -biasCutoff; iL <= biasCutoff; iL++) {
            int Lcenter = cLambda + iL;
            double deltaL = vL - Lcenter * dL;
            double deltaL2 = deltaL * deltaL;

            // Pre-compute the lambda-width exponent.
            double L2exp = exp(-deltaL2 * invLs2);

            // Mirror condition for Lambda counts.
            int lcount = Lcenter;
            double mirrorFactor = 1.0;
            if (lcount == 0 || lcount == lambdaBins - 1) {
                /**
                 * The width of the first and last bins is dLambda_2, so the
                 * mirror condition is to double their counts.
                 */
                mirrorFactor = 2.0;
            } else if (lcount < 0) {
                lcount = -lcount;
            } else if (lcount > lambdaBins - 1) {
                // number of bins past the last bin.
                lcount -= (lambdaBins - 1);
                // mirror bin
                lcount = lambdaBins - 1 - lcount;
            }

            for (int jFL = -biasCutoff; jFL <= biasCutoff; jFL++) {
                int FLcenter = cF_Lambda + jFL;
                /**
                 * For FLambda outside the count matrix the weight is 0 so we
                 * continue.
                 */
                if (FLcenter < 0 || FLcenter >= FLambdaBins) {
                    continue;
                }
                double deltaFL = vFL - (minFLambda + FLcenter * dFL + dFL_2);
                double deltaFL2 = deltaFL * deltaFL;
                double weight = mirrorFactor * recursionKernel[lcount][FLcenter];
                if (weight > 0) {
                    double e = weight * biasMag * L2exp
                            * exp(-deltaFL2 * invFLs2);
                    sum += e;
                }
            }
        }

        return sum;
    }

    /**
     * {@inheritDoc}
     * <p>
     * If necessary, allocate more space.
     */
    @Override
    protected void checkRecursionKernelSize(double dEdLambda) {
        if (dEdLambda > maxFLambda) {
            logger.info(String.format(" Current F_lambda %8.2f > maximum histogram size %8.2f.",
                    dEdLambda, maxFLambda));

            double origDeltaG = updateFLambda(false);

            int newFLambdaBins = FLambdaBins;
            while (minFLambda + newFLambdaBins * dFL < dEdLambda) {
                newFLambdaBins += 100;
            }
            double newRecursionKernel[][] = new double[lambdaBins][newFLambdaBins];
            /**
             * We have added bins above the indeces of the current counts just
             * copy them into the new array.
             */
            for (int i = 0; i < lambdaBins; i++) {
                System.arraycopy(recursionKernel[i], 0, newRecursionKernel[i], 0, FLambdaBins);
            }
            recursionKernel = newRecursionKernel;
            FLambdaBins = newFLambdaBins;
            maxFLambda = minFLambda + dFL * FLambdaBins;
            logger.info(String.format(" New histogram %8.2f to %8.2f with %d bins.\n",
                    minFLambda, maxFLambda, FLambdaBins));

            assert (origDeltaG == updateFLambda(false));

        }
        if (dEdLambda < minFLambda) {
            logger.info(String.format(" Current F_lambda %8.2f < minimum histogram size %8.2f.",
                    dEdLambda, minFLambda));

            double origDeltaG = updateFLambda(false);

            int offset = 100;
            while (dEdLambda < minFLambda - offset * dFL) {
                offset += 100;
            }
            int newFLambdaBins = FLambdaBins + offset;
            double newRecursionKernel[][] = new double[lambdaBins][newFLambdaBins];
            /**
             * We have added bins below the current counts, so their indeces
             * must be increased by: offset = newFLBins - FLBins
             */
            for (int i = 0; i < lambdaBins; i++) {
                System.arraycopy(recursionKernel[i], 0, newRecursionKernel[i], offset, FLambdaBins);
            }
            recursionKernel = newRecursionKernel;
            minFLambda = minFLambda - offset * dFL;
            FLambdaBins = newFLambdaBins;
            logger.info(String.format(" New histogram %8.2f to %8.2f with %d bins.\n",
                    minFLambda, maxFLambda, FLambdaBins));

            assert (origDeltaG == updateFLambda(false));
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Eqs. 7 and 8 from the 2012 Crystal Thermodynamics paper.
     */
    @Override
    protected double updateFLambda(boolean print) {
        double freeEnergy = 0.0;
        double minFL = Double.MAX_VALUE;

        // Total histogram weight.
        double totalWeight = 0;
        double beta = 1.0 / (Thermostat.R * temperature);
        StringBuilder stringBuilder = new StringBuilder();
        if (print) {
            stringBuilder.append("  Weight    Lambda Bins     F_Lambda Bins   <   F_L  >  Max F_L     dG        G\n");
        }
        for (int iL = 0; iL < lambdaBins; iL++) {
            int ulFL = -1;
            int llFL = -1;

            // Find the smallest FL bin.
            for (int jFL = 0; jFL < FLambdaBins; jFL++) {
                double count = recursionKernel[iL][jFL];
                if (count > 0) {
                    llFL = jFL;
                    break;
                }
            }

            // Find the largest FL bin.
            for (int jFL = FLambdaBins - 1; jFL >= 0; jFL--) {
                double count = recursionKernel[iL][jFL];
                if (count > 0) {
                    ulFL = jFL;
                    break;
                }
            }

            double lambdaCount = 0;
            // The FL range sampled for lambda bin [iL*dL .. (iL+1)*dL]
            double lla = 0.0;
            double ula = 0.0;
            double maxBias = 0;
            if (llFL == -1 || ulFL == -1) {
                FLambda[iL] = 0.0;
                minFL = 0.0;
            } else {
                double ensembleAverageFLambda = 0.0;
                double partitionFunction = 0.0;
                for (int jFL = llFL; jFL <= ulFL; jFL++) {
                    double currentFLambda = minFLambda + jFL * dFL + dFL_2;
                    double kernel = evaluateKernel(iL, jFL);
                    if (kernel > maxBias) {
                        maxBias = kernel;
                    }
                    kernel += kernelOffset[iL];
                    double weight = exp(kernel * beta);
                    if (Double.isInfinite(weight) || Double.isNaN(weight)) {
                        logger.info(format(
                                " %8.6f weight for (L=%5.3f FL=%7.1f) due to kernel %8.3f.",
                                weight, iL * dL, currentFLambda, kernel));
                        kernelOffset[iL] = -(kernel - kernelOffset[iL]);
                        logger.info(format(" Setting recursion kernel offset for L=%5.3f to %8.3f.", iL * dL, kernelOffset[iL]));
                        logger.info(format(" The weight for (L=%5.3f FL=%7.1f) is now 1.",
                                iL * dL, currentFLambda, kernel));
                        // Call this method again, using the new kernelOffset.
                        return updateFLambda(print);
                    }
                    ensembleAverageFLambda += currentFLambda * weight;
                    partitionFunction += weight;
                    lambdaCount += recursionKernel[iL][jFL];
                }
                if (minFL > maxBias) {
                    minFL = maxBias;
                }
                FLambda[iL] = ensembleAverageFLambda / partitionFunction;
                lla = minFLambda + llFL * dFL;
                ula = minFLambda + (ulFL + 1) * dFL;
            }

            // The first and last lambda bins are half size.
            double delta = dL;
            if (iL == 0 || iL == lambdaBins - 1) {
                delta = dL_2;
            }
            double deltaFreeEnergy = FLambda[iL] * delta;
            freeEnergy += deltaFreeEnergy;
            totalWeight += lambdaCount;

            if (print) {
                double llL = iL * dL - dL_2;
                double ulL = llL + dL;
                if (llL < 0.0) {
                    llL = 0.0;
                }
                if (ulL > 1.0) {
                    ulL = 1.0;
                }
                stringBuilder.append(String.format(" %6.2e  %6.4f %6.4f   %7.1f %7.1f   %8.2f  %8.2f  %8.3f %8.3f\n",
                        lambdaCount, llL, ulL, lla, ula,
                        FLambda[iL], maxBias, deltaFreeEnergy, freeEnergy));
            }
        }

        if (tempering) {
            double temperEnergy = (minFL > temperOffset) ? temperOffset - minFL : 0;
            temperingWeight = exp(temperEnergy / deltaT);
        }

        if (print || abs(freeEnergy - previousFreeEnergy) > 0.001) {
                stringBuilder.append(String.format(" Minimum Bias %8.3f", minFL));
                logger.info(stringBuilder.toString());
                double fromNumeric = integrateNumeric(FLambda, integrationType);
                logger.info(String.format(" Free energy from %s rule: %12.4f", integrationType.toString(), fromNumeric));
                previousFreeEnergy = freeEnergy;
        }

        if (print || biasCount % printFrequency == 0) {
            logger.info(String.format(" The free energy is %12.4f kcal/mol (Counts: %6.2e, Weight: %6.4f).",
                    freeEnergy, totalWeight, temperingWeight));
        }

        return freeEnergy;
    }

    /**
     * Integrates dUdL over lambda using more sophisticated techniques than midpoint rectangular integration.
     * <p>
     * The ends (from 0 to dL and 1-dL to 1) are integrated with trapezoids
     *
     * @param dUdLs dUdL at the midpoint of each bin.
     * @param type  Integration type to use.
     * @return Current delta-G estimate.
     */
    private double integrateNumeric(double[] dUdLs, IntegrationType type) {
        // Integrate between the second bin midpoint and the second-to-last bin midpoint.
        double[] midLams = Integrate1DNumeric.generateXPoints(dL, 1.0 - dL, (lambdaBins - 2), false);
        double[] midVals = Arrays.copyOfRange(dUdLs, 1, (lambdaBins - 1));
        DataSet dSet = new DoublesDataSet(midLams, midVals, false);

        double val = Integrate1DNumeric.integrateData(dSet, Integrate1DNumeric.IntegrationSide.LEFT, type);

        double dL_4 = dL_2 * 0.5;

        // Initially, assume dU/dL is exactly 0 at the endpoints. This is sometimes a true assumption.
        double val0 = 0;
        double val1 = 0;

        // If we cannot guarantee that dUdL is exactly 0 at the endpoints, interpolate.
        if (!lambdaInterface.dEdLZeroAtEnds()) {
            double recipSlopeLen = 1.0 / (dL * 0.75);

            double slope = dUdLs[0] - dUdLs[1];
            slope *= recipSlopeLen;
            val0 = dUdLs[0] + (slope * dL_4);

            slope = dUdLs[lambdaBins - 1] - dUdLs[lambdaBins - 2];
            slope *= recipSlopeLen;
            val1 = dUdLs[lambdaBins - 1] + (slope * dL_4);
            logger.fine(String.format(" Inferred dU/dL values at 0 and 1: %10.5g , %10.5g", val0, val1));
        }

        // Integrate trapezoids from 0 to the second bin midpoint, and from second-to-last bin midpoint to 1.
        val += trapezoid(0, dL_4, val0, dUdLs[0]);
        val += trapezoid(dL_4, dL, dUdLs[0], dUdLs[1]);
        val += trapezoid(1.0 - dL, 1.0 - dL_4, dUdLs[lambdaBins - 2], dUdLs[lambdaBins - 1]);
        val += trapezoid(1.0 - dL_4, 1.0, dUdLs[lambdaBins - 1], val1);

        return val;
    }

    /**
     * Integrates a trapezoid.
     *
     * @param x0  First x point
     * @param x1  Second x point
     * @param fX1 First f(x) point
     * @param fX2 Second f(x) point
     * @return The area under a trapezoid.
     */
    private double trapezoid(double x0, double x1, double fX1, double fX2) {
        double val = 0.5 * (fX1 + fX2);
        val *= (x1 - x0);
        return val;
    }

    /**
     * Sets the Dama et al tempering parameter, as a multiple of kBT.
     *
     * @param temper a double.
     */
    public void setTemperingParameter(double temper) {
        temperingFactor = temper;
        if (temperingFactor > 0.0) {
            deltaT = temperingFactor * Thermostat.R * temperature;
        } else {
            deltaT = Double.MAX_VALUE;
        }
    }

    /**
     * Send an OSRW count to all other processes while also receiving an OSRW
     * count from all other processes.
     *
     * @param lambda
     * @param dEdU
     */
    private void synchronousSend(double lambda, double dEdU) {
        /**
         * All-Gather counts from each walker.
         */
        myRecursionWeight[0] = lambda;
        myRecursionWeight[1] = dEdU;
        myRecursionWeight[2] = temperingWeight;
        try {
            world.allGather(myRecursionWeightBuf, recursionWeightsBuf);
        } catch (IOException ex) {
            String message = " Multi-walker OSRW allGather failed.";
            logger.log(Level.SEVERE, message, ex);
        }

        /**
         * Find the minimum and maximum FLambda bin for the gathered counts.
         */
        double minRequired = Double.MAX_VALUE;
        double maxRequired = Double.MIN_VALUE;
        for (int i = 0; i < numProc; i++) {
            minRequired = min(minRequired, recursionWeights[i][1]);
            maxRequired = max(maxRequired, recursionWeights[i][1]);
        }

        /**
         * Check that the FLambda range of the Recursion kernel includes both
         * the minimum and maximum FLambda value.
         */
        checkRecursionKernelSize(minRequired);
        checkRecursionKernelSize(maxRequired);

        /**
         * Increment the Recursion Kernel based on the input of each walker.
         */
        for (int i = 0; i < numProc; i++) {
            int walkerLambda = binForLambda(recursionWeights[i][0]);
            int walkerFLambda = binForFLambda(recursionWeights[i][1]);
            double weight = recursionWeights[i][2];

            /**
             * If the weight is less than 1.0, then a walker has activated
             * tempering.
             */
            if (tempering == false && weight < 1.0) {
                tempering = true;
                logger.info(String.format(" Tempering activated due to recieved weight of (%8.6f)", weight));
            }

            if (resetStatistics && recursionWeights[i][0] > lambdaResetValue) {
                recursionKernel = new double[lambdaBins][FLambdaBins];
                resetStatistics = false;
                logger.info(String.format(" Cleared OSRW histogram (Lambda = %6.4f).", recursionWeights[i][0]));
            }

            recursionKernel[walkerLambda][walkerFLambda] += weight;
        }
    }

    /**
     * Send an OSRW count to all other processes.
     *
     * @param lambda
     * @param dEdU
     */
    private void asynchronousSend(double lambda, double dEdU) {
        myRecursionWeight[0] = lambda;
        myRecursionWeight[1] = dEdU;
        myRecursionWeight[2] = temperingWeight;

        for (int i = 0; i < numProc; i++) {
            try {
                world.send(i, myRecursionWeightBuf);
            } catch (Exception ex) {
                String message = " Asynchronous Multiwalker OSRW send failed.";
                logger.log(Level.SEVERE, message, ex);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean destroy() {
        if (receiveThread != null && receiveThread.isAlive()) {
            double[] killMessage = new double[]{Double.NaN, Double.NaN, Double.NaN};
            DoubleBuf killBuf = DoubleBuf.buffer(killMessage);
            try {
                logger.fine(" Sending the termination message.");
                world.send(rank, killBuf);
                logger.fine(" Termination message was sent successfully.");
                logger.fine(String.format(" Receive thread alive %b status %s", receiveThread.isAlive(), receiveThread.getState()));
            } catch (Exception ex) {
                String message = String.format(" Asynchronous Multiwalker OSRW termination signal " +
                        "failed to be sent for process %d.", rank);
                logger.log(Level.SEVERE, message, ex);
            }
        } else {
            logger.fine(" ReceiveThread was either not initialized, or is not alive. This is the case for the Histogram script.");
        }
        return potential.destroy();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getdEdL() {
        return getTotaldEdLambda();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getd2EdL2() {
        // TODO: Add in a finite-difference implementation of d2U/dL2.
        // TODO: Add in support for analytic third derivatives ONLY for non-softcore systems.
        // TODO: Derive d^3U/dL^3 for AMOEBA. This would not be easy.
        throw new UnsupportedOperationException(" Second derivatives of the bias are not implemented, as they require third derivatives of the potential.");
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public void getdEdXdL(double[] gradient) {
        // TODO: Add in a finite-difference implementation of d2U/dXdL.
        // TODO: Add in support for analytic third derivatives ONLY for non-softcore systems.
        // TODO: Derive d^3U/dL^3 for AMOEBA. This would not be easy.
        throw new UnsupportedOperationException(" Second derivatives of the bias are not implemented, as they require third derivatives of the potential.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean dEdLZeroAtEnds() {
        return false;
    }
    @Override
    public void setLambdaWriteOut(double lambdaWriteOut){
        this.lambdaWriteOut = lambdaWriteOut;
    }

    private class ReceiveThread extends Thread {

        final double recursionCount[];
        final DoubleBuf recursionCountBuf;

        public ReceiveThread() {
            recursionCount = new double[3];
            recursionCountBuf = DoubleBuf.buffer(recursionCount);
        }

        @Override
        public void run() {
            while (true) {
                try {
                    world.receive(null, recursionCountBuf);
                } catch (InterruptedIOException ioe) {
                    logger.log(Level.WARNING, " ReceiveThread was interrupted at world.receive; " +
                            "future message passing may be in an error state!", ioe);
                    break;
                } catch (IOException e) {
                    String message = e.getMessage();
                    logger.log(Level.WARNING, message, e);
                }

                // 3x NaN is a message (usually sent by the same process) indicating that it is time to shut down.
                boolean terminateSignal = Arrays.stream(recursionCount).allMatch(Double::isNaN);
                if (terminateSignal) {
                    logger.fine(" Termination signal (3x NaN) received; ReceiveThread shutting down");
                    break;
                }

                /**
                 * Check that the FLambda range of the Recursion kernel includes
                 * both the minimum and maximum FLambda value.
                 */
                checkRecursionKernelSize(recursionCount[1]);

                /**
                 * Increment the Recursion Kernel based on the input of current
                 * walker.
                 */
                int walkerLambda = binForLambda(recursionCount[0]);
                int walkerFLambda = binForFLambda(recursionCount[1]);
                double weight = recursionCount[2];

                /**
                 * If the weight is less than 1.0, then a walker has activated
                 * tempering.
                 */
                if (!tempering && weight < 1.0) {
                    tempering = true;
                    logger.info(String.format(" Tempering activated due to recieved weight of (%8.6f)", weight));
                }

                if (resetStatistics && recursionCount[0] > lambdaResetValue) {
                    recursionKernel = new double[lambdaBins][FLambdaBins];
                    resetStatistics = false;
                    logger.info(String.format(" Cleared OSRW histogram (Lambda = %6.4f).", recursionCount[0]));
                }

                /**
                 * Increase the Recursion Kernel based on the input of current
                 * walker.
                 */
                recursionKernel[walkerLambda][walkerFLambda] += weight;
                if (this.isInterrupted()) {
                    logger.log(Level.FINE, " ReceiveThread was interrupted; ceasing execution");
                    // No pending message receipt, so no warning.
                    break;
                }
            }
        }

    }

    /**
     * Write out the TT-OSRW Histogram.
     */
    private class TTOSRWHistogramWriter extends PrintWriter {

        public TTOSRWHistogramWriter(Writer writer) {
            super(writer);
        }

        public void writeHistogramFile() {
            printf("Temperature     %15.3f\n", temperature);
            printf("Lambda-Mass     %15.8e\n", thetaMass);
            printf("Lambda-Friction %15.8e\n", thetaFriction);
            printf("Bias-Mag        %15.8e\n", biasMag);
            printf("Bias-Cutoff     %15d\n", biasCutoff);
            printf("Count-Interval  %15d\n", countInterval);
            printf("Lambda-Bins     %15d\n", lambdaBins);
            printf("FLambda-Bins    %15d\n", FLambdaBins);
            printf("Flambda-Min     %15.8e\n", minFLambda);
            printf("Flambda-Width   %15.8e\n", dFL);
            int flag = 0;
            if (tempering) {
                flag = 1;
            }
            printf("Tempering       %15d\n", flag);
            for (int i = 0; i < lambdaBins; i++) {
                printf("%g", recursionKernel[i][0]);
                for (int j = 1; j < FLambdaBins; j++) {
                    printf(" %g", recursionKernel[i][j]);
                }
                println();
            }
        }
    }

    /**
     * Write out the current value of Lambda, its velocity and the number of
     * counts.
     */
    private class TTOSRWLambdaWriter extends PrintWriter {

        public TTOSRWLambdaWriter(Writer writer) {
            super(writer);
        }

        public void writeLambdaFile() {
            printf("Lambda          %15.8f\n", lambda);
            printf("Lambda-Velocity %15.8e\n", halfThetaVelocity);
            printf("Steps-Taken     %15d\n", energyCount);
        }
    }

    /**
     * Read in the TT-OSRW Histogram.
     */
    private class TTOSRWHistogramReader extends BufferedReader {

        public TTOSRWHistogramReader(Reader reader) {
            super(reader);
        }

        public void readHistogramFile() {
            try {
                temperature = Double.parseDouble(readLine().split(" +")[1]);
                thetaMass = Double.parseDouble(readLine().split(" +")[1]);
                thetaFriction = Double.parseDouble(readLine().split(" +")[1]);
                biasMag = Double.parseDouble(readLine().split(" +")[1]);
                biasCutoff = Integer.parseInt(readLine().split(" +")[1]);
                countInterval = Integer.parseInt(readLine().split(" +")[1]);

                lambdaBins = Integer.parseInt(readLine().split(" +")[1]);
                FLambda = new double[lambdaBins];
                dL = 1.0 / (lambdaBins - 1);
                dL_2 = dL / 2.0;

                FLambdaBins = Integer.parseInt(readLine().split(" +")[1]);
                minFLambda = Double.parseDouble(readLine().split(" +")[1]);
                dFL = Double.parseDouble(readLine().split(" +")[1]);
                dFL_2 = dFL / 2.0;

                int flag = Integer.parseInt(readLine().split(" +")[1]);
                if (flag != 0) {
                    tempering = true;
                } else {
                    tempering = false;
                }

                // Allocate memory for the recursion kernel.
                recursionKernel = new double[lambdaBins][FLambdaBins];
                for (int i = 0; i < lambdaBins; i++) {
                    String counts[] = readLine().split(" +");
                    for (int j = 0; j < FLambdaBins; j++) {
                        recursionKernel[i][j] = Double.parseDouble(counts[j]);
                    }
                }
            } catch (Exception e) {
                String message = " Invalid OSRW Histogram file.";
                logger.log(Level.SEVERE, message, e);
            }
        }
    }

    /**
     * Read in the current value of Lambda, its velocity and the number of
     * counts.
     */
    private class TTOSRWLambdaReader extends BufferedReader {

        public TTOSRWLambdaReader(Reader reader) {
            super(reader);
        }

        public void readLambdaFile() {
            readLambdaFile(true);
        }

        public void readLambdaFile(boolean resetEnergyCount) {
            try {
                lambda = Double.parseDouble(readLine().split(" +")[1]);
                halfThetaVelocity = Double.parseDouble(readLine().split(" +")[1]);
                setLambda(lambda);
            } catch (Exception e) {
                String message = " Invalid OSRW Lambda file.";
                logger.log(Level.SEVERE, message, e);
            }
            if (!resetEnergyCount) {
                try {
                    energyCount = Integer.parseUnsignedInt(readLine().split(" +")[1]);
                } catch (Exception e) {
                    logger.log(Level.WARNING, String.format(" Could not find number of steps taken in OSRW Lambda file: %s", e.toString()));
                }
            }
        }
    }

}
