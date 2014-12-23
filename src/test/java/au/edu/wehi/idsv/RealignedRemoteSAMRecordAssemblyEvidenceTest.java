package au.edu.wehi.idsv;

import com.google.common.collect.Sets;



public class RealignedRemoteSAMRecordAssemblyEvidenceTest extends RemoteEvidenceTest {
	@Override
	public RemoteEvidence makeRemote(BreakendSummary bs, String allBases, String realignCigar, boolean realignNegativeStrand) {
		return new RealignedRemoteSAMRecordAssemblyEvidence(getContext(), AES(),
				AssemblyFactory.createAnchored(getContext(), AES(), bs.direction, Sets.<DirectedEvidence>newHashSet(), bs.referenceIndex, bs.start, anchorLength(allBases, realignCigar), B(allBases), B(allBases), 0, 0).getSAMRecord(),
				realignSAM(bs, allBases, realignCigar, realignNegativeStrand));
	}
	@Override
	public DirectedBreakpoint makeLocal(BreakendSummary bs, String allBases, String realignCigar, boolean realignNegativeStrand) {
		return new RealignedSAMRecordAssemblyEvidence(getContext(), AES(),
				AssemblyFactory.createAnchored(getContext(), AES(), bs.direction, Sets.<DirectedEvidence>newHashSet(), bs.referenceIndex, bs.start, anchorLength(allBases, realignCigar), B(allBases), B(allBases), 0, 0).getSAMRecord(),
				realignSAM(bs, allBases, realignCigar, realignNegativeStrand));
	}
}
