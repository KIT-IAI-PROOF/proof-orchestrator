"""
This module contains a Python Wrapper that enables the User to easily implement default code as simulators into PROOF.
The User can replace "Model logic" with his own code.
"""
import argparse
from sys import exit
from typing import Tuple, Dict, Any
import math
import time


from proofcore.base.basewrapper import BaseWrapper, main
from proofcore.models.ValueMessage import ValueMessage
from proofcore.models.NotifyMessage import NotifyMessage
from proofcore.models.SyncMessage import SyncMessage

p = argparse.ArgumentParser()
p.add_argument('--name', '-n', dest='name', default="model")                    #example: "BHKW"
p.add_argument('--directory', '-w', dest='directory')                           #example: "/home/BHKW"
p.add_argument('--file', '-f', dest='file')                                     #example: "BHKW.fmu"
p.add_argument('--inputs', '-i', dest='inputs', default='[]', type=str)         #example: "['setpoint', 'power']"
p.add_argument('--outputs', '-o', dest='outputs', default='[]', type=str)       #example: "['P_el', 'Q']"
options, arguments = p.parse_known_args()


class elecWrapper(BaseWrapper):
    def __init__(self, opt=options) -> None:
        self.T_ambient = 293.15
        self.T0 = 273.15
        self.R0 = 100
        self.alpha = 3.85e-2
        self.U = 7.00
        self.Current = []
        self.power_loss = ""
        self.T = 0.0
        self.tmax = 0
        super(elecWrapper, self).__init__(
            inputs=opt.inputs,
            outputs=opt.outputs,
            directory=opt.directory
        )

    def init(self) -> Tuple[ValueMessage, NotifyMessage]:
        """
        comment
        :param lifecycle_id:
        :return:
        """
        # Model logic
        self.R_T(self.T_ambient)
        print("Init Step: T set to ", self.T_ambient)
        return super(elecWrapper, self).init()

    def step(self) -> Tuple[ValueMessage, NotifyMessage]:
        # Model logic
        if self.communicationPoint+1 < self.tmax:
            self.logger.info("The temperature is at " + str(self.T)+ " K at communicationPoint" + str(self.communicationPoint))
            time.sleep(2)
            self.R_T(self.T)
            return super(elecWrapper, self).step()
        else:
            self.finalize()

    def finalize(self) -> Tuple[ValueMessage, NotifyMessage]:
        # Model logic
        self.logger.info("Current:" + str(self.Current))
        return super(elecWrapper, self).finalize()

    def R_T(self,T): # Calcualte the resistance which is dependant on the temperature
        R = self.R0 * math.exp(self.alpha * (T - self.T0)) #resistance at the current temperature
        self.power_loss = str((self.U ** 2) / R)
        self.Current.append(self.U / R)

    def set_variables(self, variables: Dict) -> None:
        for key, value in variables.items():
            setattr(self, key, value)

    def get_data(self) -> Dict[Any, Any]:
        print(self.outputs)
        return {obj: getattr(self, obj) for obj in self.outputs}


if __name__ == '__main__':
    wrapper = elecWrapper()
    try:
        main(wrapper=wrapper)
    except KeyboardInterrupt:
        exit(0)
