package ffx.potential.extended;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import ffx.numerics.Potential;
import ffx.potential.ForceFieldEnergy;
import ffx.potential.MolecularAssembly;
import ffx.potential.bonded.Atom;
import ffx.potential.bonded.BondedUtils;
import ffx.potential.bonded.MultiResidue;
import ffx.potential.bonded.Polymer;
import ffx.potential.bonded.Residue;
import ffx.potential.bonded.ResidueEnumerations.AminoAcid3;
import ffx.potential.extended.ExtUtils.SB;
import ffx.potential.parameters.ForceField;

/**
 * Helper methods to define titration-specific phenomena.
 */
public class TitrationUtils {
    private TitrationUtils() {} // utility class
    private static final boolean threeStateHistidines = false; // not yet implemented

    public static MultiResidue titrationFactory(MolecularAssembly mola, Residue res) {
        // Get reference to FFE.
        ForceField ff = mola.getForceField();
        Potential potential = mola.getPotentialEnergy();
        if (!(potential instanceof ForceFieldEnergy)) {
            SB.warning("TitrationFactory only supported by ForceFieldEnergy potentials.");
            throw new IllegalStateException();
        }
        ForceFieldEnergy ffe = (ForceFieldEnergy) potential;
        // Create new titration state.
        Titr t = TitrationUtils.titrationLookup(res);
        String targetName = (t.protForm != res.getAminoAcid3())
                ? t.protForm.toString() : t.deprotForm.toString();
        int resNumber = res.getResidueNumber();
        Residue.ResidueType resType = res.getResidueType();
        Residue newRes = new Residue(targetName, resNumber, resType);
        // Wrap both states in a MultiResidue.
        MultiResidue multiRes = new MultiResidue(res, ff, ffe);
        Polymer polymer = findResiduePolymer(res, mola);
        polymer.addMultiResidue(multiRes);
        multiRes.addResidue(newRes);
        multiRes.setActiveResidue(res);
//        setMultiResXYZIndices(mola, multiRes);
        propagateInactiveResidues(multiRes, false);
        ffe.reInit();
        multiRes.getActive().getBondList();
        return multiRes;
    }

    private static void setMultiResXYZIndices(MolecularAssembly mola, MultiResidue multi) {
        for (Residue res : multi.getConsideredResidues()) {
            for (Atom atom : res.getAtomList()) {
                if (atom.getIndex() < 1) {
//                    atom.setXyzIndex(MolecularAssembly.atomIndexer++);
                }
            }
        }
    }

    /**
     * Find Titr object for given res; start here for tri-histidine implementation.
     */
    public static Titr titrationLookup(Residue res) {
        AminoAcid3 source = AminoAcid3.valueOf(res.getName());
        if (source == AminoAcid3.HIS || source == AminoAcid3.HID || source == AminoAcid3.HIE) {
            return threeStateHistidines ? Titr.ZtoH : Titr.UtoH;
        }
        for (Titr titr : Titr.values()) {
            if (titr.deprotForm == source || titr.protForm == source) {
                return titr;
            }
        }
        SB.warning("No titration lookup found for residue %s", res);
        return null;
    }

    private static Polymer findResiduePolymer(Residue residue, MolecularAssembly mola) {
        Polymer[] polymers = mola.getChains();
        Optional<Polymer> polymer = IntStream.range(0, polymers.length).parallel().mapToObj((i) -> polymers[i]).filter((p) -> p.getChainID().compareTo(residue.getChainID()) == 0).findAny();
        if (!polymer.isPresent()) {
            SB.warning(" Polymer not found for residue %s", residue);
            throw new IllegalStateException();
        }
        return polymer.get();
    }

    /**
     * Copies atomic coordinates from each active residue to its inactive
     * counterparts. Inactive hydrogen coordinates are updated by geometry
     * with the propagated heavies.
     */
    private static void propagateInactiveResidues(MultiResidue multiRes, boolean propagateDynamics) {
        // Propagate all atom coordinates from active residues to their inactive counterparts.
        Residue active = multiRes.getActive();
        String activeResName = active.getName();
        List<Residue> inactives = multiRes.getInactive();
        for (Atom activeAtom : active.getAtomList()) {
            String activeName = activeAtom.getName();
            for (Residue inactive : inactives) {
                Atom inactiveAtom = (Atom) inactive.getAtomNode(activeName);
                if (inactiveAtom != null) {
                    // Propagate position and gradient.
                    double[] activeXYZ = activeAtom.getXYZ(null);
                    inactiveAtom.setXYZ(activeXYZ);
                    double[] grad = new double[3];
                    activeAtom.getXYZGradient(grad);
                    inactiveAtom.setXYZGradient(grad[0], grad[1], grad[2]);
                    if (propagateDynamics) {
                        // Propagate velocity, acceleration, and previous acceleration.
                        double[] activeVelocity = new double[3];
                        activeAtom.getVelocity(activeVelocity);
                        inactiveAtom.setVelocity(activeVelocity);
                        double[] activeAccel = new double[3];
                        activeAtom.getAcceleration(activeAccel);
                        inactiveAtom.setAcceleration(activeAccel);
                        double[] activePrevAcc = new double[3];
                        activeAtom.getPreviousAcceleration(activePrevAcc);
                        inactiveAtom.setPreviousAcceleration(activePrevAcc);
                    }
                } else {
                    if (activeName.equals("C") || activeName.equals("O") || activeName.equals("N") || activeName.equals("CA") || activeName.equals("H") || activeName.equals("HA")) {
                        // Backbone atoms aren't supposed to exist in inactive multiResidue components; so no problem.
                    } else if (isTitratableHydrogen(activeAtom)) {
                        /** i.e.
                        ((activeResName.equals("LYS") && activeName.equals("HZ3"))
                        || (activeResName.equals("TYR") && activeName.equals("HH"))
                        || (activeResName.equals("CYS") && activeName.equals("HG"))
                        || (activeResName.equals("HIS") && (activeName.equals("HD1") || activeName.equals("HE2")))
                        || (activeResName.equals("HID") && activeName.equals("HD1"))
                        || (activeResName.equals("HIE") && activeName.equals("HE2"))
                        || (activeResName.equals("ASH") && activeName.equals("HD2"))
                        || (activeResName.equals("GLH") && activeName.equals("HE2")))   */
                        // These titratable protons are handled below; so no problem.
                    } else {
                        // Now we have a problem.
                        SB.warning("Couldn't propagate inactive MultiResidue atom: %s: %s, %s", multiRes, activeName, activeAtom);
                    }
                }
            }
        }
        rebuildStrandedProtons(multiRes);
    }

    public static boolean isTitratableHydrogen(Atom atom) {
        String name = atom.getName();
        switch (atom.getResidueName()) {
            case "LYS":
                if (name.equals("HZ3")) {
                    return true;
                }
                break;
            case "TYR":
                if (name.equals("HH")) {
                    return true;
                }
                break;
            case "CYS":
                if (name.equals("HG")) {
                    return true;
                }
                break;
            case "HIS":
                if (name.equals("HD1") || name.equals("HE2")) {
                    return true;
                }
                break;
            case "HID":
                if (name.equals("HD1")) {
                    return true;
                }
                break;
            case "HIE":
                if (name.equals("HE2")) {
                    return true;
                }
                break;
            case "ASH":
                if (name.equals("HD2")) {
                    return true;
                }
                break;
            case "GLH":
                if (name.equals("HE2")) {
                    return true;
                }
                break;
        }
        return false;
    }

    /**
     * Rebuild stranded titratable protons from ideal geometry.
     * "Stranded protons" are titrating H+ atoms on inactive MultiRes members; when
     * propagating new coordinates to inactive residues, no coords/velocity exist for them.
     */
    private static void rebuildStrandedProtons(MultiResidue multiRes) {
        // If inactive residue is a protonated form, move the stranded hydrogen to new coords (based on propagated heavies).
        // Also give the stranded hydrogen a maxwell velocity and remove its accelerations.
        List<Residue> inactives = multiRes.getInactive();
        for (Residue inactive : inactives) {
            List<Atom> resetMe = new ArrayList<>();
            switch (inactive.getName()) {
                case "LYS":
                    {
                        Atom HZ3 = (Atom) inactive.getAtomNode("HZ3");
                        Atom NZ = (Atom) inactive.getAtomNode("NZ");
                        Atom CE = (Atom) inactive.getAtomNode("CE");
                        Atom HZ1 = (Atom) inactive.getAtomNode("HZ1");
                        BondedUtils.intxyz(HZ3, NZ, 1.02, CE, 109.5, HZ1, 109.5, -1);
                        resetMe.add(HZ3);
                        break;
                    }
                case "ASH":
                    {
                        Atom HD2 = (Atom) inactive.getAtomNode("HD2");
                        Atom OD2 = (Atom) inactive.getAtomNode("OD2");
                        Atom CG = (Atom) inactive.getAtomNode("CG");
                        Atom OD1 = (Atom) inactive.getAtomNode("OD1");
                        BondedUtils.intxyz(HD2, OD2, 0.98, CG, 108.7, OD1, 0.0, 0);
                        resetMe.add(HD2);
                        break;
                    }
                case "GLH":
                    {
                        Atom HE2 = (Atom) inactive.getAtomNode("HE2");
                        Atom OE2 = (Atom) inactive.getAtomNode("OE2");
                        Atom CD = (Atom) inactive.getAtomNode("CD");
                        Atom OE1 = (Atom) inactive.getAtomNode("OE1");
                        BondedUtils.intxyz(HE2, OE2, 0.98, CD, 108.7, OE1, 0.0, 0);
                        resetMe.add(HE2);
                        break;
                    }
                case "HIS":
                    {
                        Atom HE2 = (Atom) inactive.getAtomNode("HE2");
                        Atom NE2 = (Atom) inactive.getAtomNode("NE2");
                        Atom CD2 = (Atom) inactive.getAtomNode("CD2");
                        Atom CE1 = (Atom) inactive.getAtomNode("CE1");
                        Atom HD1 = (Atom) inactive.getAtomNode("HD1");
                        Atom ND1 = (Atom) inactive.getAtomNode("ND1");
                        Atom CG = (Atom) inactive.getAtomNode("CG");
                        Atom CB = (Atom) inactive.getAtomNode("CB");
                        BondedUtils.intxyz(HE2, NE2, 1.02, CD2, 126.0, CE1, 126.0, 1);
                        BondedUtils.intxyz(HD1, ND1, 1.02, CG, 126.0, CB, 0.0, 0);
                        resetMe.add(HE2);
                        resetMe.add(HD1);
                        break;
                    }
                case "HID":
                    {
                        Atom HD1 = (Atom) inactive.getAtomNode("HD1");
                        Atom ND1 = (Atom) inactive.getAtomNode("ND1");
                        Atom CG = (Atom) inactive.getAtomNode("CG");
                        Atom CB = (Atom) inactive.getAtomNode("CB");
                        BondedUtils.intxyz(HD1, ND1, 1.02, CG, 126.0, CB, 0.0, 0);
                        resetMe.add(HD1);
                        break;
                    }
                case "HIE":
                    {
                        Atom HE2 = (Atom) inactive.getAtomNode("HE2");
                        Atom NE2 = (Atom) inactive.getAtomNode("NE2");
                        Atom CD2 = (Atom) inactive.getAtomNode("CD2");
                        Atom CE1 = (Atom) inactive.getAtomNode("CE1");
                        BondedUtils.intxyz(HE2, NE2, 1.02, CD2, 126.0, CE1, 126.0, 1);
                        resetMe.add(HE2);
                        break;
                    }
                case "CYS":
                    {
                        Atom HG = (Atom) inactive.getAtomNode("HG");
                        Atom SG = (Atom) inactive.getAtomNode("SG");
                        Atom CB = (Atom) inactive.getAtomNode("CB");
                        Atom CA = (Atom) inactive.getAtomNode("CA");
                        BondedUtils.intxyz(HG, SG, 1.34, CB, 96.0, CA, 180.0, 0);
                        resetMe.add(HG);
                        break;
                    }
                case "TYR":
                    {
                        Atom HH = (Atom) inactive.getAtomNode("HH");
                        Atom OH = (Atom) inactive.getAtomNode("OH");
                        Atom CZ = (Atom) inactive.getAtomNode("CZ");
                        Atom CE2 = (Atom) inactive.getAtomNode("CE2");
                        BondedUtils.intxyz(HH, OH, 0.97, CZ, 108.0, CE2, 0.0, 0);
                        resetMe.add(HH);
                        break;
                    }
                default:
            }
            for (Atom a : resetMe) {
                a.setXYZGradient(0, 0, 0);
                a.setVelocity(ExtUtils.singleRoomtempMaxwell(a.getMass()));
                a.setAcceleration(new double[]{0, 0, 0});
                a.setPreviousAcceleration(new double[]{0, 0, 0});
            }
        }
    }

    /**
     * All described as protonation reactions.
     */
    public enum Titr {
        ctoC(8.18, +60.168, AminoAcid3.CYD, AminoAcid3.CYS),
        Dtod(3.90, +53.188, AminoAcid3.ASP, AminoAcid3.ASH),
        Etoe(4.25, +59.390, AminoAcid3.GLU, AminoAcid3.GLH),
        ktoK(10.53, -50.440, AminoAcid3.LYD, AminoAcid3.LYS),
        ytoY(10.07, +34.961, AminoAcid3.TYD, AminoAcid3.TYR),
        UtoH(6.00, -42.923, AminoAcid3.HID, AminoAcid3.HIS),
        ZtoH(6.00, +00.000, AminoAcid3.HIE, AminoAcid3.HIS),
        TerminusNH3toNH2(8.23, +00.00, AminoAcid3.UNK, AminoAcid3.UNK),
        TerminusCOOHtoCOO(3.55, +00.00, AminoAcid3.UNK, AminoAcid3.UNK);
        
        public final double pKa;
        public final double refEnergy;
        public final AminoAcid3 deprotForm;
        public final AminoAcid3 protForm;

        Titr(double pKa, double refEnergy, AminoAcid3 source, AminoAcid3 target) {
            this.pKa = pKa;
            this.refEnergy = refEnergy;
            this.deprotForm = source;
            this.protForm = target;
        }
    }

}