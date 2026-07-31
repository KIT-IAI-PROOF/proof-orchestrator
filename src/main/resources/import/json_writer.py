#!/usr/bin/python
# Call example: python3 src/main/resources/import/json_writer.py -i '["url", "input"]'
# Then value: {"type": "VALUE", "lifeCycleId": 1, "data": {"url": "http://jsonplaceholder.typicode.com/todos/1"}, "time": 0}
# Then value: {"type": "VALUE", "lifeCycleId": 1, "data": {"input": {"test":"test"}}, "time": 0}
# Then sync: {"type": "SYNC", "lifeCycleId": 1, "stepType": "STEP", "time": 0}

from argparse import ArgumentParser
from json import dumps, loads
from sys import stdin, stdout

p = ArgumentParser()
p.add_argument('--inputs', '-i', dest='inputs', default='[]', type=str)
p.add_argument('--outputs', '-o', dest='outputs', default='[]', type=str)
options, arguments = p.parse_known_args()


class Program:

    def __init__(self):
        self.writer_url = ""
        self.input = {}
        self.outputs = loads(options.outputs)
        self.inputs = loads(options.inputs)
        # print("PROGRAM INITIALIZED WITH I={i} O={o}".format(i=self.inputs, o=self.outputs), flush=True)

    def init(self):
        # print("EXECUTING INIT", flush=True)
        self.notify(0)

    def step(self):
        # Url has to be set in advance (init)
        # print("EXECUTING STEP", flush=True)
        print("WOULD POST: {0} TO: {1}".format(dumps(self.input), self.writer_url))
        self.notify(1)

    def finalize(self):
        # print("EXECUTING FINALIZE", flush=True)
        self.notify(2)

    def set_data(self, data):
        for key, value in data.items():
            setattr(self, key, value)

    def get_data(self, lifecycle_id):
        result = {}
        for output in self.outputs:
            if hasattr(self, output):
                result[output] = getattr(self, output)
            else:
                pass
        message = {
            "type": "VALUE",
            "lifeCycleId": lifecycle_id,
            "data": result,
            "time": 0
        }
        stdout.write(dumps(message) + '\n')

    @staticmethod
    def notify(lifecycle_id):
        message = {
            "type": "LIFECYCLENOTIFY",
            "status": "COMPLETED",
            "lifeCycleId": lifecycle_id,
            "time": 0
        }
        stdout.write(dumps(message) + '\n')
        # print("NOTIFY SEND", flush=True)

    def main(self) -> None:
        while True:
            line = stdin.readline()
            message = loads(line)
            message_type = message["type"]
            if message_type == "VALUE":
                # print("PROGRAM RECEIVED VALUEMESSAGE={0}".format(message), flush=True)
                message_data = message["data"]
                self.set_data(message_data)
            elif message_type == "SYNC":
                # print("PROGRAM RECEIVED SYNCMESSAGE={0}".format(message), flush=True)
                message_step = message["stepType"]
                if message_step == "INIT":
                    self.init()
                elif message_step == "STEP":
                    self.step()
                elif message_step == "FINALIZE":
                    self.finalize()


if __name__ == '__main__':
    program = Program()
    program.main()
