"""Real Docker boundary tests. No mocks, assumptions, or skipped requirements."""
import base64
import concurrent.futures
import hashlib
import os
from pathlib import Path
import tempfile
import threading
import unittest
import uuid
from worker import Supervisor, PROFILE, decode, require

class WorkerBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = tempfile.TemporaryDirectory()
        cls.worker = Supervisor(os.environ['BATTLECODE_TEST_DOCKER'],
            Path('.tools/phase8f/image-id.txt').read_text().strip(), uuid.uuid4().hex, cls.config.name)
        report = cls.worker.verify()
        if not report['executable']:
            cls.worker.close(); cls.config.cleanup()
            raise RuntimeError('Live sandbox gate failed: ' + str(report))
    @classmethod
    def tearDownClass(cls):
        cls.worker.close(); cls.config.cleanup()
    def request(self):
        source = b'function chooseMove(o){return o.legalMoves[0];}'
        return dict(executionId=str(uuid.uuid4()), limits=PROFILE,
            submission=dict(submissionId=str(uuid.uuid4()), version=1,
                sourceBase64=base64.b64encode(source).decode(), sourceSha256=hashlib.sha256(source).hexdigest()),
            observation=dict(apiVersion='bot-1',gameId='ott-board-9x9-v1',playerId='A',turn=1,
                boardSize=9,board=[],legalMoves=[dict(pieceId='A-R1',to=dict(row=1,col=0))],remainingTimeMs=1000))
    def execute(self, request):
        result = concurrent.futures.Future()
        self.worker.execute(request, result.set_result)
        return result.result(timeout=10)
    def test_hash_tampering_rejected_before_guest(self):
        request=self.request(); request['submission']['sourceSha256']='0'*64
        self.assertEqual('REQUEST_INTEGRITY',self.execute(request)['evidence'])
    def test_version_tampering_rejected(self):
        request=self.request(); request['submission']['version']=True
        self.assertEqual('REQUEST_INTEGRITY',self.execute(request)['evidence'])
    def test_duplicate_id_has_same_result_and_conflict_is_infrastructure(self):
        request=self.request(); first=self.execute(request)
        self.assertEqual(first,self.execute(request))
        request['submission']['version']=2
        self.assertEqual('ID_CONFLICT',self.execute(request)['evidence'])
    def test_cancel_running_guest_and_cleanup(self):
        ready=threading.Event(); cancelled=threading.Event()
        def stop():
            if ready.wait(5): cancelled.set()
        thread=threading.Thread(target=stop); thread.start()
        result=self.worker.run_guest(mode='--probe-wall',cancelled=cancelled,ready=ready)
        thread.join(timeout=6)
        self.assertTrue(ready.is_set()); self.assertFalse(thread.is_alive())
        self.assertEqual('MATCH_CANCELLED',result['failure'])
        self.assertEqual(b'',self.worker.cli('ps','--all','--filter','label=battlecode.worker='+self.worker.owner,'--format','{{.ID}}').stdout.strip())
    def test_optimization_cannot_disable_integrity_checks(self):
        with self.assertRaises(ValueError): require(False)
        with self.assertRaises(ValueError): decode(b'{"key":1,"key":2}')
    def test_zz_match_generation_retires_old_requests_and_bounds_cache(self):
        first=self.request(); first['generation']=1
        self.assertIn('action',self.execute(first))
        next_request=self.request(); next_request['generation']=2
        self.assertIn('action',self.execute(next_request))
        self.assertEqual(1,len(self.worker.records))
        self.assertEqual('STALE_MATCH_GENERATION',self.execute(first)['evidence'])

if __name__=='__main__': unittest.main(verbosity=2)
