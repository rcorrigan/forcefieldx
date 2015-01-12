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
package ffx.algorithms;

import java.util.ArrayList;
import java.util.Random;
import java.util.logging.Logger;

import static org.apache.commons.math3.util.FastMath.exp;
import static org.apache.commons.math3.util.FastMath.random;

import ffx.potential.MolecularAssembly;
import ffx.potential.bonded.Polymer;
import ffx.potential.bonded.Residue;

/**
 * @author S. LuCore
 */
public class Protonate implements MonteCarloListener {

    private static final Logger logger = Logger.getLogger(Protonate.class.getName());
    /**
     * The MD thermostat.
     */
    private final Thermostat thermostat;
    /**
     * Boltzmann's constant is kcal/mol/Kelvin.
     */
    private static final double boltzmann = 0.0019872041;
    /**
     * Energy of the system at initialization.
     */
    private final double systemReferenceEnergy;
    /**
     * Simulation pH.
     */
    private final double pH;
    /**
     * The current MD step.
     */
    private int stepCount = 0;
    /**
     * Number of simulation steps between MC move attempts.
     */
    private static int mcStepFrequency;
    /**
     * Number of accepted MD moves.
     */
    private int numMovesAccepted;
    /**
     * Titratable residues in the system.
     */
    private ArrayList<Residue> titratableResidues;
    private Random rng = new Random();

    /**
     * Construct a Monte-Carlo protonation state switching mechanism.
     *
     * @param molAss the molecular assembly
     * @param mcStepFrequency number of MD steps between switch attempts
     * @param pH the simulation pH
     * @param thermostat the MD thermostat
     */
    Protonate(MolecularAssembly molAss, int mcStepFrequency, double pH, Thermostat thermostat) {
        //initialize stepcount and the number of accepted moves
        numMovesAccepted = 0;

        this.mcStepFrequency = mcStepFrequency;
        this.pH = pH;
        this.thermostat = thermostat;
        systemReferenceEnergy = molAss.getPotentialEnergy().getTotalEnergy();

        // Identify titratable residues.
        Polymer polymers[] = molAss.getChains();
        for (int i = 0; i < polymers.length; i++) {
            ArrayList<Residue> residues = polymers[i].getResidues();
            for (int j = 0; j < residues.size(); j++) {
                if (isTitratable(residues.get(j).getName())) {
                    titratableResidues.add(residues.get(j));
                }
            }
        }
    }

    /**
     * True if passed residue name has multiple protonation states.
     *
     * @param residueName
     * @return if this residue has multiple protonation states
     */
    private boolean isTitratable(String residueName) {
        for (Titratable titrName : Titratable.values()) {
            if (residueName.equalsIgnoreCase(titrName.toString())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mcUpdate(MolecularAssembly molAss) {
        stepCount++;
        if (stepCount % mcStepFrequency != 0) {
            return false;
        }

        // Randomly choose a target titratable residue to attempt protonation switch.
        int random = rng.nextInt(titratableResidues.size());
        Residue targetResidue = titratableResidues.get(random);

        // Switch titration state for chosen residue.
        switchProtonationState(targetResidue);

        String name = (titratableResidues.get(random)).getName();
        double pKaref = Titratable.valueOf(name).pKa;
        double dG_ref = Titratable.valueOf(name).refEnergy;
        double temperature = thermostat.getCurrentTemperature();
        double kT = boltzmann * temperature;
        double dG_elec = molAss.getPotentialEnergy().getTotalEnergy() - systemReferenceEnergy;

        /**
         * dG_elec = electrostatic energy component of the titratable residue
         * dG_ref = electrostatic component of the transition energy for the
         * reference compound
         */
        double dG_MC = kT * (pH - pKaref) * Math.log(10) + dG_elec - dG_ref;

        // Test Monte-Carlo criterion.
        if (dG_MC < 0) {
            numMovesAccepted++;
            return true;
        }
        double boltzmann = exp(-dG_MC / kT);
        double metropolis = random();
        if (metropolis < boltzmann) {
            numMovesAccepted++;
            return true;
        }

        // Undo titration state change if criterion was not accepted.
        switchProtonationState(targetResidue);
        return false;
    }

    /**
     * Switch the protonation state of target residue and reinitialize FF.
     *
     * @param residue
     */
    private void switchProtonationState(Residue residue) {
        return;
    }

    /**
     * Get the current MC acceptance rate.
     *
     * @return the acceptance rate.
     */
    @Override
    public double getAcceptanceRate() {
        // Intentional integer division.
        int numTries = stepCount / mcStepFrequency;
        return (double) numMovesAccepted / numTries;
    }

    /**
     * Constant values for intrinsic pKa and reference energy of deprotonation.
     */
    public enum Titratable {

        ARG(12.48, 1.00),
        ASP(4.00, 1.00),
        CYS(8.18, 1.00),
        GLU(4.25, 1.00),
        HIS(6.00, 1.00),
        LYS(10.53, 1.00),
        TYR(10.07, 1.00);

        public final double pKa;
        public final double refEnergy;

        Titratable(double pKa, double refEnergy) {
            this.pKa = pKa;
            this.refEnergy = refEnergy;
        }
    };
}
