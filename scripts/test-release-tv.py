#!/usr/bin/env python3
"""Offline release-runner boundaries and recovery tests; no real builds or remote writes."""
import importlib.util
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
import subprocess
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('runner', Path(__file__).with_name('release-tv.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def published(version, code):
    return dict(versionName=version, versionCode=code, tag='kotlin-v'+version)


class RunnerTests(unittest.TestCase):
    def test_next_version_and_code_use_both_platforms_and_source(self):
        self.assertEqual(m.select_version(('0.1.6', 47), [published('0.1.5', 90), published('0.1.6', 47)], set()), ('0.1.7', 91))
        self.assertEqual(m.select_version(('0.1.6', 47), [published('0.2.0', 50)], set()), ('0.2.1', 51))

    def test_explicit_version_collision_and_non_increasing_version_fail(self):
        self.assertEqual(m.select_version(('0.1.6',47), [], set(), '0.2.0'), ('0.2.0',48))
        for version in ['0.1.6', '0.1.5', '0.1.07', '../0.1.7']:
            with self.assertRaises(m.Failure):
                m.select_version(('0.1.6',47), [], set(), version)
        with self.assertRaises(m.Failure):
            m.select_version(('0.1.6',47), [], {'kotlin-v0.1.7'})

    def test_source_version_follows_real_gradle_defaults(self):
        text='versionCode = System.getenv("CODE")?.toInt() ?: 47\nversionName = System.getenv("NAME") ?: "0.1.6"'
        self.assertEqual(m.source_version(text), ('0.1.6',47))

    def test_default_plan_has_no_mutation_build_or_job_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            with patch.object(m,'ROOT',root), patch.object(m.publisher,'credentials',return_value={'GITHUB_TOKEN':'test','GITCODE_TOKEN':'test'}), \
                 patch.object(m,'inventory',return_value=dict(version='0.1.7',code=48,base='a'*40)), \
                 patch.object(m,'notes_for',return_value='notes'), patch.object(m,'prepare_commit') as prepare, \
                 patch.object(m,'build') as build, patch.object(m,'sync_tags') as tags, patch.object(m,'publish') as publish, \
                 patch('sys.argv',['release-tv']):
                m.main()
                for mutation in [prepare,build,tags,publish]: mutation.assert_not_called()
                self.assertFalse((root/'outputs').exists())

    def test_dirty_main_blocks_publish_before_remote_inventory(self):
        with patch.object(m.publisher,'credentials',return_value={'GITHUB_TOKEN':'test','GITCODE_TOKEN':'test'}), \
             patch.object(m,'git',side_effect=['main',' M README.md']), patch.object(m,'inventory') as inventory, \
             patch('sys.argv',['release-tv','--publish']):
            with self.assertRaises(m.Failure): m.main()
            inventory.assert_not_called()

    def test_resume_uses_recorded_version_without_new_inventory_or_prepare(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);checkpoint=root/'outputs/release-jobs/0.1.7/state.json';checkpoint.parent.mkdir(parents=True)
            state=dict(version='0.1.7',tag='kotlin-v0.1.7',prepared='a'*40,source='b'*40,built=True)
            checkpoint.write_text(json.dumps(state))
            with patch.object(m,'ROOT',root), patch.object(m.publisher,'credentials',return_value={'GITHUB_TOKEN':'test','GITCODE_TOKEN':'test'}), \
                 patch.object(m,'git',return_value=''), patch.object(m,'inventory') as inventory, patch.object(m,'prepare_commit') as prepare, \
                 patch.object(m,'build') as build, patch.object(m,'sync_tags'), patch.object(m,'publish'), \
                 patch('sys.argv',['release-tv','--publish','--resume','0.1.7']):
                m.main();inventory.assert_not_called();prepare.assert_not_called()
                self.assertEqual(build.call_args.args[0]['version'],'0.1.7')

    def test_unfinished_job_blocks_new_version_allocation(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);checkpoint=root/'outputs/release-jobs/0.1.7/state.json';checkpoint.parent.mkdir(parents=True)
            checkpoint.write_text(json.dumps(dict(version='0.1.7',complete=False)))
            with patch.object(m,'ROOT',root), patch.object(m.publisher,'credentials',return_value={'GITHUB_TOKEN':'test','GITCODE_TOKEN':'test'}), \
                 patch.object(m,'inventory') as inventory, patch('sys.argv',['release-tv','--publish']):
                with self.assertRaises(m.Failure): m.main()
                inventory.assert_not_called()

    def test_local_tag_conflict_never_pushes(self):
        with patch.object(m.subprocess,'run',return_value=SimpleNamespace(returncode=0)), patch.object(m,'git',return_value='other') as git:
            with self.assertRaises(m.Failure): m.sync_tags(dict(tag='kotlin-v0.1.7',source='expected'),{})
            self.assertFalse(any(c.args[0]=='push' for c in git.call_args_list))

    def test_published_manifest_identity_is_checked_and_legacy_suffix_is_supported(self):
        release=dict(tag_name='kotlin-v0.0.8',assets=[dict(name='update.json',browser_download_url='https://github.com/manifest')])
        data=dict(tag='kotlin-v0.0.8',packageName='com.hgtv.tv',versionName='0.0.8-android6',versionCode=8)
        with patch.object(m,'read_public',return_value=json.dumps(data).encode()): self.assertEqual(m.manifest(release),data)
        data['packageName']='io.github.wuzuo.hgtv'
        with patch.object(m,'read_public',return_value=json.dumps(data).encode()): self.assertEqual(m.manifest(release),data)
        data['packageName']='unrelated.app'
        with patch.object(m,'read_public',return_value=json.dumps(data).encode()):
            with self.assertRaises(m.Failure): m.manifest(release)
        data['packageName']='io.github.wuzuo.hgtv'
        data['versionCode']=0
        with patch.object(m,'read_public',return_value=json.dumps(data).encode()):
            with self.assertRaises(m.Failure): m.manifest(release)

    def test_missing_historical_manifest_stops_instead_of_guessing_code(self):
        with self.assertRaises(m.Failure): m.manifest(dict(tag_name='kotlin-v0.1.6',assets=[]))

    def test_legacy_release_reads_actual_apk_code_and_checks_all_variants(self):
        release=dict(tag_name='kotlin-v0.0.10',assets=[dict(name='android6.apk',browser_download_url='https://github.com/a'),
                                                  dict(name='android8.apk',browser_download_url='https://github.com/b')])
        with patch.object(m,'tools_env',return_value=({},Path('/tools'))), patch.object(m,'read_public',return_value=b'apk'), \
             patch.object(m,'run',side_effect=["package: name='com.hgtv.tv' versionCode='11' versionName='0.0.10-android6'",
                                               "package: name='com.hgtv.tv' versionCode='12' versionName='0.0.10-android8'"]):
            data=m.manifest(release)
            self.assertEqual(data['versionCode'],12)
            self.assertEqual(data['apk']['name'],'android8.apk')

    def test_cached_bundle_hash_change_stops_resume_without_building(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);folder=root/'bundle';folder.mkdir();apk=folder/'app.apk';apk.write_bytes(b'new')
            (folder/'update.json').write_text(json.dumps(dict(versionName='0.1.7',versionCode=48)))
            checkpoint=root/'state.json';state=dict(built=True,directory='bundle',version='0.1.7',code=48,hashes={'app.apk':'old'})
            with patch.object(m,'ROOT',root), patch.object(m,'tools_env',return_value=({},root)), \
                 patch.object(m.publisher,'bundle',return_value=('kotlin-v0.1.7','notes',[apk])), patch.object(m,'run') as run:
                with self.assertRaises(m.Failure): m.build(state,checkpoint,{})
                run.assert_not_called()

    def test_signature_mismatch_blocks_publication(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);folder=root/'bundle';folder.mkdir();apk=folder/'app.apk';apk.write_bytes(b'apk')
            (folder/'update.json').write_text(json.dumps(dict(versionName='0.1.7',versionCode=48)))
            (root/'baseline.apk').write_bytes(b'old')
            state=dict(built=True,directory='bundle',version='0.1.7',code=48,baselineHash=hashlib.sha256(b'old').hexdigest())
            with patch.object(m,'ROOT',root), patch.object(m,'tools_env',return_value=({},root)), \
                 patch.object(m.publisher,'bundle',return_value=('kotlin-v0.1.7','notes',[apk])), \
                 patch.object(m,'certificate',side_effect=[(['new'],True),(['old'],True)]), patch.object(m,'publish') as publish:
                with self.assertRaises(m.Failure): m.build(state,root/'state.json',{})
                publish.assert_not_called()

    def test_only_unreleased_tv_fix_and_feature_titles_become_automatic_notes(self):
        with patch.object(m,'git',return_value='fix: 修复找剧返回\nchore: 调整工具\nfeat(search): 新增联想\nfix: 修复找剧返回'):
            notes=m.notes_for(dict(version='0.1.7',baselineTag='kotlin-v0.1.6',base='a'*40))
            self.assertEqual(notes.count('修复找剧返回'),1);self.assertIn('新增联想',notes);self.assertNotIn('调整工具',notes)

    def test_package_transition_notes_require_separate_install(self):
        with patch.object(m,'git',return_value='feat: HGTV 遥控器搜索与新包名'):
            notes=m.notes_for(dict(version='0.1.8',baselineTag='kotlin-v0.1.7',base='a'*40,previousPackage='com.hgtv.tv'))
            self.assertIn('需单独安装',notes);self.assertIn('不自动迁移',notes)
            self.assertNotIn('可覆盖更新并保留数据',notes)
            self.assertNotIn('先在旧版备份',notes)

    def test_git_status_leading_space_is_preserved(self):
        with patch.object(m.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=' M README.md\n')):
            self.assertEqual(m.git('status','--porcelain'),' M README.md')

    def test_main_advancing_after_checks_blocks_merge(self):
        github=Mock();github.api.side_effect=[dict(merged=False,state='open'),dict(object=dict(sha='other'))]
        with self.assertRaises(m.Failure): m.merge(dict(pr=1,base='expected'),Path('unused'),github)
        self.assertFalse(any('PUT' in c.args for c in github.api.call_args_list))

    def test_preparation_creates_only_release_commit_and_can_adopt_it_after_interruption(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            def git(*args): return subprocess.check_output(['git',*args],cwd=root,text=True,stderr=subprocess.DEVNULL).strip()
            git('init','-b','main');git('config','user.name','Release Test');git('config','user.email','test@example.invalid')
            for name in m.VERSION_FILES:
                p=root/name;p.parent.mkdir(parents=True,exist_ok=True)
                p.write_text('versionCode = System.getenv("CODE")?.toInt() ?: 47\nversionName = System.getenv("NAME") ?: "0.1.6"\n' if name==m.BUILD else '当前 TV 0.1.6（versionCode 47）\n')
            (root/'.gitignore').write_text('outputs/\n');git('add','.');git('commit','-m','chore: 测试基线')
            state=dict(base=git('rev-parse','HEAD'),version='0.1.7',code=48,previous='0.1.6',notes='新更新说明')
            checkpoint=root/'outputs/state.json';checkpoint.parent.mkdir()
            with patch.object(m,'ROOT',root):
                m.prepare_commit(state,checkpoint)
                prepared=state['prepared'];self.assertEqual(git('rev-parse','HEAD^'),state['base'])
                self.assertEqual(m.source_version((root/m.BUILD).read_text()),('0.1.7',48))
                self.assertTrue(git('log','-1','--format=%s').startswith('chore:'))
                del state['prepared'];m.prepare_commit(state,checkpoint)
                self.assertEqual(state['prepared'],prepared);self.assertEqual(git('status','--porcelain'),'')

    def test_cached_valid_bundle_is_reused_without_gradle(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);folder=root/'bundle';folder.mkdir();apk=folder/'app.apk';apk.write_bytes(b'apk')
            (folder/'update.json').write_text(json.dumps(dict(versionName='0.1.7',versionCode=48)))
            (root/'baseline.apk').write_bytes(b'old')
            state=dict(built=True,directory='bundle',version='0.1.7',code=48,baselineHash=hashlib.sha256(b'old').hexdigest())
            with patch.object(m,'ROOT',root), patch.object(m,'tools_env',return_value=({},root)), \
                 patch.object(m.publisher,'bundle',return_value=('kotlin-v0.1.7','notes',[apk])), \
                 patch.object(m,'certificate',return_value=(['same'],True)), patch.object(m,'run') as run:
                m.build(state,root/'state.json',{});run.assert_not_called()
                self.assertTrue(json.loads((root/'state.json').read_text())['built'])

    def test_both_upload_platforms_preflight_before_any_release_creation(self):
        github=Mock();gitcode=Mock();github.preflight.return_value=None;gitcode.preflight.side_effect=m.Failure('unavailable')
        with patch.object(m.publisher,'Publisher',side_effect=[github,gitcode]), \
             patch.object(m.publisher,'bundle',return_value=('kotlin-v0.1.7','notes',[])):
            with self.assertRaises(m.Failure):
                m.publish(dict(tag='kotlin-v0.1.7',notes='notes',directory='bundle'),Path('unused'),dict(GITHUB_TOKEN='test',GITCODE_TOKEN='test'))
            github.stage.assert_not_called();gitcode.stage.assert_not_called()

    def test_unexpected_draft_appearing_after_plan_is_not_adopted(self):
        github=Mock();gitcode=Mock();github.preflight.return_value=dict(id=1,draft=True);gitcode.preflight.return_value=None
        with patch.object(m.publisher,'Publisher',side_effect=[github,gitcode]), \
             patch.object(m.publisher,'bundle',return_value=('kotlin-v0.1.7','notes',[])):
            with self.assertRaises(m.Failure):
                m.publish(dict(tag='kotlin-v0.1.7',notes='notes',directory='bundle'),Path('unused'),dict(GITHUB_TOKEN='test',GITCODE_TOKEN='test'))
            github.stage.assert_not_called();gitcode.stage.assert_not_called()

    def test_merged_tree_different_from_checked_tree_cannot_be_tagged(self):
        github=Mock();github.api.return_value=dict(merged=True,merge_commit_sha='b'*40)
        with patch.object(m,'git',side_effect=['','changed-tree','checked-tree']), patch.object(m,'save') as save:
            with self.assertRaises(m.Failure): m.merge(dict(pr=1,prepared='a'*40),Path('unused'),github)
            save.assert_not_called()


if __name__=='__main__': unittest.main()
